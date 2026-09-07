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

    @Transactional(readOnly = true)
    public ProjectFolderDtos.FolderDetail getFolder(Long projectId, User currentUser) {
        if (currentUser == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        boolean isAdmin = currentUser.isAdminLike();

        if (!isAdmin && !projectRepository.isMember(projectId, currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        com.dataentry.security.TenantGuard.assertOwnership(p);

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

    public void assertCanUploadTo(Long projectId, User currentUser) {
        if (currentUser == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (projectId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project is required");
        boolean isAdmin = currentUser.isAdminLike();
        if (!isAdmin && !projectRepository.isMember(projectId, currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        if (!projectRepository.existsById(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
    }

    public ProjectFolderDtos.QuickUploadResult quickUpload(Long projectId,
                                                           Long departmentId,
                                                           User currentUser,
                                                           List<MultipartFile> files,
                                                           List<String> titles) {
        if (currentUser == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (files == null || files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick at least one file");
        }
        assertCanUploadTo(projectId, currentUser);

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

            try {
                ok.add(createTicketAndAttachWith(projectId, departmentId, currentUser, title,
                        ticketId -> documentService.upload(ticketId, title, file, currentUser, true)));
            } catch (ResponseStatusException rse) {
                failed.add(new ProjectFolderDtos.QuickUploadFailure(
                        safeFilename(file.getOriginalFilename()),
                        rse.getReason() == null ? "Upload failed" : rse.getReason()));
            }
        }

        return new ProjectFolderDtos.QuickUploadResult(ok.size(), failed.size(), ok, failed);
    }

    public TicketDtos.TicketResponse createTicketAndAttach(Long projectId,
                                                           Long departmentId,
                                                           User currentUser,
                                                           String title,
                                                           TicketDocumentService.IncomingFile file) {
        assertCanUploadTo(projectId, currentUser);
        String cleanTitle = (title == null || title.isBlank())
                ? titleFromFilename(file.originalFilename())
                : title.trim();
        return createTicketAndAttachWith(projectId, departmentId, currentUser, cleanTitle,
                ticketId -> documentService.attach(ticketId, cleanTitle, file, currentUser, true));
    }

    @FunctionalInterface
    private interface Attacher {
        TicketDtos.DocumentResponse attach(Long ticketId);
    }

    private TicketDtos.TicketResponse createTicketAndAttachWith(Long projectId,
                                                                Long departmentId,
                                                                User currentUser,
                                                                String title,
                                                                Attacher attacher) {
        Long ticketId;
        try {
            ticketId = ticketService.createAttachmentTicket(currentUser, projectId, departmentId, title).getId();
        } catch (ResponseStatusException rse) {
            throw rse.getReason() == null
                    ? new ResponseStatusException(rse.getStatusCode(), "Could not create ticket")
                    : rse;
        } catch (RuntimeException e) {
            log.warn("Quick-upload ticket create failed for \"{}\": {}", title, e.toString());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not create ticket");
        }

        try {
            attacher.attach(ticketId);
        } catch (ResponseStatusException rse) {
            ticketService.deleteByIdUnchecked(ticketId);
            throw rse.getReason() == null
                    ? new ResponseStatusException(rse.getStatusCode(), "Upload failed")
                    : rse;
        } catch (RuntimeException e) {
            log.warn("Quick-upload attach failed for ticket {}: {}", ticketId, e.toString());
            ticketService.deleteByIdUnchecked(ticketId);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Upload failed");
        }

        try {
            return ticketService.getOne(ticketId, currentUser, currentUser.isAdminLike());
        } catch (RuntimeException e) {
            log.warn("Quick-upload post-load failed for ticket {}: {}", ticketId, e.toString());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Uploaded, but folder view refresh failed");
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
