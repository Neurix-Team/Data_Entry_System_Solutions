package com.dataentry.service;

import com.dataentry.dto.ProjectFolderDtos;
import com.dataentry.dto.TicketDtos;
import com.dataentry.model.Project;
import com.dataentry.model.Ticket;
import com.dataentry.model.TicketStatus;
import com.dataentry.model.User;
import com.dataentry.repository.ProjectRepository;
import com.dataentry.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Presents projects as folders and their tickets as the folder's contents. Non-admin
 * callers only see tickets they themselves submitted; admins/super-admins see every
 * ticket in the project.
 *
 * <p>Uses the "for folder view" repository queries that skip the {@code members} eager
 * fetch. Legacy data can have cross-team members on a project, and the tenant listener's
 * {@code @PostLoad} would throw NOT_FOUND on any such user — turning the whole folder
 * grid into a 404 for the admin. The folder UI doesn't need members either, so skipping
 * the fetch fixes the bug at the source.
 */
@Service
public class ProjectFolderService {

    private static final Logger log = LoggerFactory.getLogger(ProjectFolderService.class);

    private final ProjectRepository projectRepository;
    private final TicketRepository ticketRepository;
    private final TicketService ticketService;
    private final TicketDocumentService documentService;
    private final Localizer localizer;

    public ProjectFolderService(ProjectRepository projectRepository,
                                TicketRepository ticketRepository,
                                TicketService ticketService,
                                TicketDocumentService documentService,
                                Localizer localizer) {
        this.projectRepository = projectRepository;
        this.ticketRepository = ticketRepository;
        this.ticketService = ticketService;
        this.documentService = documentService;
        this.localizer = localizer;
    }

    @Transactional(readOnly = true)
    public List<ProjectFolderDtos.FolderSummary> listFolders(User currentUser) {
        if (currentUser == null) return List.of();
        boolean isAdmin = currentUser.isAdminLike();

        List<Project> projects = isAdmin
                ? projectRepository.findAllForFolderView()
                : projectRepository.findMemberProjectsForFolderView(currentUser.getId());

        List<ProjectFolderDtos.FolderSummary> out = new ArrayList<>(projects.size());
        for (Project p : projects) {
            long total, pending, approved;
            if (isAdmin) {
                total = ticketRepository.countByProjectId(p.getId());
                pending = ticketRepository.countByProjectIdAndStatus(p.getId(), TicketStatus.IN_PROGRESS)
                        + ticketRepository.countByProjectIdAndStatus(p.getId(), TicketStatus.REVIEW);
                approved = ticketRepository.countByProjectIdAndStatus(p.getId(), TicketStatus.COMPLETED);
            } else {
                total = ticketRepository.countByProjectIdAndSubmittedById(p.getId(), currentUser.getId());
                pending = ticketRepository.countByProjectIdAndSubmittedByIdAndStatus(
                        p.getId(), currentUser.getId(), TicketStatus.IN_PROGRESS)
                        + ticketRepository.countByProjectIdAndSubmittedByIdAndStatus(
                        p.getId(), currentUser.getId(), TicketStatus.REVIEW);
                approved = ticketRepository.countByProjectIdAndSubmittedByIdAndStatus(
                        p.getId(), currentUser.getId(), TicketStatus.COMPLETED);
            }
            out.add(new ProjectFolderDtos.FolderSummary(
                    p.getId(),
                    localizer.pick(p.getNameEn(), p.getNameAr(), p.getName()),
                    p.getNameEn(),
                    p.getNameAr(),
                    localizer.pick(p.getSubtitleEn(), p.getSubtitleAr(), p.getSubtitle()),
                    p.getSubtitleEn(),
                    p.getSubtitleAr(),
                    total,
                    pending,
                    approved,
                    p.getStatus().name()
            ));
        }
        out.sort(Comparator.<ProjectFolderDtos.FolderSummary>comparingLong(s -> s.total() == 0 ? 1 : 0)
                .thenComparing(Comparator.comparingLong(ProjectFolderDtos.FolderSummary::pending).reversed()));
        return out;
    }

    /**
     * Load a single folder. USER must be a member of the project (checked via a count query
     * so the members collection isn't hydrated — that would trip the tenant listener on
     * cross-team member rows). ADMIN/SUPER_ADMIN can open any folder.
     */
    @Transactional(readOnly = true)
    public ProjectFolderDtos.FolderDetail getFolder(Long projectId, User currentUser) {
        if (currentUser == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        boolean isAdmin = currentUser.isAdminLike();

        if (!isAdmin && !projectRepository.isMember(projectId, currentUser.getId())) {
            // Membership check first so a USER poking at a project id they aren't on gets a
            // clean 404 instead of a partial payload.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        List<Ticket> tickets = isAdmin
                ? ticketRepository.findAllByProjectIdOrderBySubmittedAtDesc(projectId)
                : ticketRepository.findAllByProjectIdAndSubmittedByIdOrderBySubmittedAtDesc(
                        projectId, currentUser.getId());

        List<TicketDtos.TicketResponse> serialized = tickets.stream()
                .map(ticketService::toDto)
                .toList();

        return new ProjectFolderDtos.FolderDetail(
                p.getId(),
                localizer.pick(p.getNameEn(), p.getNameAr(), p.getName()),
                p.getNameEn(),
                p.getNameAr(),
                serialized
        );
    }

    /**
     * Multi-file quick-upload into a folder. Each file becomes its own ticket:
     *   1. Create a REVIEW-status ticket owned by the caller, in the given project.
     *   2. Attach the file. If the attachment fails, roll back the ticket so we don't
     *      leave orphan "empty" tickets that the folder view can't distinguish from a
     *      legitimate write-later ticket.
     *
     * <p>Per-file failures don't abort the whole batch — the response reports both what
     * succeeded and what failed with a reason, so the user isn't left guessing about a
     * silent partial success.
     *
     * <p>Titles are the parallel array to files. If the client passes fewer titles than
     * files (or none at all), the missing ones are derived from the filename. If it
     * passes more titles than files, the extras are ignored.
     *
     * <p><b>Not @Transactional</b> on purpose: each file's create + upload runs inside
     * the nested calls' own transactions. Wrapping the batch would let a single per-file
     * failure taint the outer transaction with "rollback-only", which then throws
     * UnexpectedRollbackException at commit and buries the partial-success semantics
     * we want the response to carry.
     */
    public ProjectFolderDtos.QuickUploadResult quickUpload(Long projectId,
                                                           User currentUser,
                                                           List<MultipartFile> files,
                                                           List<String> titles) {
        if (currentUser == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (files == null || files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick at least one file");
        }

        // Access check: USER must be a project member; ADMIN/SUPER_ADMIN skip. The isMember
        // query runs as its own repo tx and returns a boolean — no lazy load, no entity
        // listener trip.
        boolean isAdmin = currentUser.isAdminLike();
        if (!isAdmin && !projectRepository.isMember(projectId, currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        if (!projectRepository.existsById(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }

        List<TicketDtos.TicketResponse> ok = new ArrayList<>();
        List<ProjectFolderDtos.QuickUploadFailure> failed = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            if (file == null || file.isEmpty()) {
                failed.add(new ProjectFolderDtos.QuickUploadFailure(
                        file == null ? "?" : safeFilename(file.getOriginalFilename()),
                        "Empty file"));
                continue;
            }
            String requestedTitle = titles != null && i < titles.size() ? titles.get(i) : null;
            String title = (requestedTitle == null || requestedTitle.isBlank())
                    ? titleFromFilename(file.getOriginalFilename())
                    : requestedTitle.trim();

            processOneFile(projectId, currentUser, title, file, ok, failed);
        }

        return new ProjectFolderDtos.QuickUploadResult(ok.size(), failed.size(), ok, failed);
    }

    /**
     * Handle one file end-to-end. Every write goes through {@code REQUIRES_NEW}-scoped
     * methods on TicketService / TicketDocumentService, so a per-file failure is contained
     * — the outer batch loop keeps running and the response records both what landed and
     * what failed.
     */
    private void processOneFile(Long projectId, User currentUser, String title,
                                MultipartFile file,
                                List<TicketDtos.TicketResponse> ok,
                                List<ProjectFolderDtos.QuickUploadFailure> failed) {
        Long ticketId;
        try {
            ticketId = ticketService.createAttachmentTicket(currentUser, projectId, title).getId();
        } catch (ResponseStatusException rse) {
            failed.add(new ProjectFolderDtos.QuickUploadFailure(
                    safeFilename(file.getOriginalFilename()),
                    rse.getReason() == null ? "Could not create ticket" : rse.getReason()));
            return;
        } catch (Exception e) {
            log.warn("Quick-upload ticket create failed for {}: {}", file.getOriginalFilename(), e.toString());
            failed.add(new ProjectFolderDtos.QuickUploadFailure(
                    safeFilename(file.getOriginalFilename()),
                    "Could not create ticket"));
            return;
        }

        try {
            documentService.upload(ticketId, title, file, currentUser, true);
        } catch (ResponseStatusException rse) {
            ticketService.deleteByIdUnchecked(ticketId);
            failed.add(new ProjectFolderDtos.QuickUploadFailure(
                    safeFilename(file.getOriginalFilename()),
                    rse.getReason() == null ? "Upload failed" : rse.getReason()));
            return;
        } catch (Exception e) {
            log.warn("Quick-upload attach failed for ticket {}: {}", ticketId, e.toString());
            ticketService.deleteByIdUnchecked(ticketId);
            failed.add(new ProjectFolderDtos.QuickUploadFailure(
                    safeFilename(file.getOriginalFilename()),
                    "Upload failed"));
            return;
        }

        // Load the fully-hydrated ticket back for the response envelope. Uses the same
        // getOne path the ticket-view page relies on, so the shape is guaranteed to match
        // what the frontend already handles. Auth check inside getOne is satisfied because
        // the caller submitted the ticket themselves (or is an admin).
        try {
            ok.add(ticketService.getOne(ticketId, currentUser, currentUser.isAdminLike()));
        } catch (Exception e) {
            log.warn("Quick-upload post-load failed for ticket {}: {}", ticketId, e.toString());
            // Even if the response envelope can't be built, the upload succeeded — record a
            // stub success so the client doesn't over-report failure.
            failed.add(new ProjectFolderDtos.QuickUploadFailure(
                    safeFilename(file.getOriginalFilename()),
                    "Uploaded, but folder view refresh failed"));
        }
    }

    private String titleFromFilename(String name) {
        if (name == null || name.isBlank()) return "file";
        String noExt = name.replaceAll("\\.[^./\\\\]+$", "");
        String cleaned = noExt.replaceAll("[_\\-.]+", " ").replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? name : cleaned;
    }

    private String safeFilename(String name) {
        if (name == null || name.isBlank()) return "?";
        return name.length() > 120 ? name.substring(0, 117) + "…" : name;
    }
}
