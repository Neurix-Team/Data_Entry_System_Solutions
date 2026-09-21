package com.dataentry.service;

import com.dataentry.dto.RecycleBinDtos;
import com.dataentry.model.User;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.ProjectRepository;
import com.dataentry.repository.TicketRepository;
import com.dataentry.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Recycle bin: everything a soft delete parked (tickets and projects) is listed,
 * restored and — past the retention window — purged from here. The native repository
 * queries bypass the entity-level @Where on Ticket, which is exactly what a bin
 * needs: those rows are invisible everywhere else by design.
 */
@Service
public class RecycleBinService {

    private static final Logger log = LoggerFactory.getLogger(RecycleBinService.class);

    private final TicketRepository tickets;
    private final ProjectRepository projects;
    private final DepartmentRepository departments;
    private final AuditService audit;
    private final ObjectProvider<DepartmentService> departmentServiceProvider;
    private final ObjectProvider<TicketDocumentService> documentServiceProvider;
    private final ObjectProvider<RecycleBinService> selfProvider;
    private final int retentionDays;

    public RecycleBinService(TicketRepository tickets,
                             ProjectRepository projects,
                             DepartmentRepository departments,
                             AuditService audit,
                             ObjectProvider<DepartmentService> departmentServiceProvider,
                             ObjectProvider<TicketDocumentService> documentServiceProvider,
                             ObjectProvider<RecycleBinService> selfProvider,
                             @Value("${app.recycle-bin.retention-days:30}") int retentionDays) {
        this.tickets = tickets;
        this.projects = projects;
        this.departments = departments;
        this.audit = audit;
        this.departmentServiceProvider = departmentServiceProvider;
        this.documentServiceProvider = documentServiceProvider;
        this.selfProvider = selfProvider;
        this.retentionDays = Math.max(1, retentionDays);
    }

    /** Team-wide bin (admin view). Super admins see every team. */
    @Transactional(readOnly = true)
    public RecycleBinDtos.BinPage listTickets(int page, int size) {
        Long teamId = listScope();
        if (teamId == null && !TenantContext.isSuperAdmin()) return emptyPage(page, size);
        int safeSize = clampSize(size);
        long total = tickets.countDeletedTickets(teamId, null);
        int safePage = clampPage(page, total, safeSize);
        List<RecycleBinDtos.BinItem> items = tickets
                .findDeletedTickets(teamId, null, safeSize, safePage * safeSize)
                .stream().map(this::toItem).toList();
        return new RecycleBinDtos.BinPage(items, total, totalPages(total, safeSize), safePage, safeSize);
    }

    /** A user's own binned entries — the personal safety net. */
    @Transactional(readOnly = true)
    public RecycleBinDtos.BinPage listOwnTickets(User current, int page, int size) {
        if (current == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Long teamId = listScope();
        if (teamId == null && !TenantContext.isSuperAdmin()) return emptyPage(page, size);
        int safeSize = clampSize(size);
        long total = tickets.countDeletedTickets(teamId, current.getId());
        int safePage = clampPage(page, total, safeSize);
        List<RecycleBinDtos.BinItem> items = tickets
                .findDeletedTickets(teamId, current.getId(), safeSize, safePage * safeSize)
                .stream().map(this::toItem).toList();
        return new RecycleBinDtos.BinPage(items, total, totalPages(total, safeSize), safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public RecycleBinDtos.BinPage listProjects(int page, int size) {
        Long teamId = listScope();
        if (teamId == null && !TenantContext.isSuperAdmin()) return emptyPage(page, size);
        int safeSize = clampSize(size);
        long total = projects.countDeletedProjects(teamId);
        int safePage = clampPage(page, total, safeSize);
        List<RecycleBinDtos.BinItem> items = projects
                .findDeletedProjects(teamId, safeSize, safePage * safeSize)
                .stream().map(this::toItem).toList();
        return new RecycleBinDtos.BinPage(items, total, totalPages(total, safeSize), safePage, safeSize);
    }

    private RecycleBinDtos.BinItem toItem(TicketRepository.DeletedTicketRow r) {
        return new RecycleBinDtos.BinItem(r.getId(), "TICKET",
                r.getTitle(), r.getTitleEn(), r.getTitleAr(), r.getStatus(),
                r.getSubmittedByUsername(), r.getDeletedAt(), r.getDeletedByName(),
                daysLeft(r.getDeletedAt()));
    }

    private RecycleBinDtos.BinItem toItem(ProjectRepository.DeletedProjectRow r) {
        return new RecycleBinDtos.BinItem(r.getId(), "PROJECT",
                r.getName(), r.getNameEn(), r.getNameAr(), r.getStatus(),
                null, r.getDeletedAt(), r.getDeletedByName(),
                daysLeft(r.getDeletedAt()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Long listScope() {
        return TenantContext.isSuperAdmin() ? null : TenantContext.getTeamId();
    }

    /** Same 404-not-403 convention as TenantGuard: foreign rows simply do not exist. */
    private void assertTeamAccess(Long rowTeamId) {
        if (TenantContext.isSuperAdmin()) return;
        Long expected = TenantContext.getTeamId();
        if (expected == null || rowTeamId == null || !expected.equals(rowTeamId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
    }

    private long daysLeft(Instant deletedAt) {
        if (deletedAt == null) return 0;
        long spent = ChronoUnit.DAYS.between(deletedAt, Instant.now());
        return Math.max(0, retentionDays - spent);
    }

    private int clampSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }

    private int clampPage(int page, long total, int size) {
        int maxPage = size <= 0 ? 0 : (int) Math.max(0, (total - 1) / size);
        return Math.min(Math.max(page, 0), maxPage);
    }

    private int totalPages(long total, int size) {
        return size <= 0 ? 0 : (int) ((total + size - 1) / size);
    }

    private RecycleBinDtos.BinPage emptyPage(int page, int size) {
        int safeSize = clampSize(size);
        return new RecycleBinDtos.BinPage(List.of(), 0, 0, clampPage(page, 0, safeSize), safeSize);
    }

    // ── Restore ───────────────────────────────────────────────────────────────

    @Transactional
    public void restoreTicket(Long id, User current, boolean adminRequest) {
        TicketRepository.DeletedTicketRow row = tickets.findDeletedTicketById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        assertTeamAccess(row.getTeamId());
        if (!adminRequest && (current == null || !current.getId().equals(row.getSubmittedById()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only restore your own entries");
        }
        if (tickets.restoreTicket(id) == 0) {
            // Lost a race — purged or already restored between the read and the update.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        audit.record(AuditService.Action.RESTORE, AuditService.EntityType.TICKET, id, null);
    }

    @Transactional
    public void restoreProject(Long id) {
        if (projects.countBinnedProjectById(id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        assertTeamAccess(projects.teamIdOfAnyProject(id));
        if (projects.restoreProject(id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        audit.record(AuditService.Action.RESTORE, AuditService.EntityType.PROJECT, id, null);
    }

    // ── Permanent purge (admin action from the bin) ───────────────────────────

    @Transactional
    public void purgeTicket(Long id) {
        if (tickets.countBinnedTicketById(id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        assertTeamAccess(tickets.teamIdOfAnyTicket(id));
        purgeTicketInternal(id);
    }

    @Transactional
    public void purgeProject(Long id) {
        if (projects.countBinnedProjectById(id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        assertTeamAccess(projects.teamIdOfAnyProject(id));
        purgeProjectInternal(id);
    }

    private void purgeTicketInternal(Long id) {
        tickets.purgeTicketDocuments(id);
        tickets.purgeTicketResources(id);
        tickets.purgeTicketFieldValues(id);
        tickets.purgeTicketRow(id);
        TicketDocumentService docs = documentServiceProvider == null
                ? null : documentServiceProvider.getIfAvailable();
        if (docs != null) docs.purgeTicketDirectory(id);
        audit.record(AuditService.Action.PURGE, AuditService.EntityType.TICKET, id, null);
    }

    private void purgeProjectInternal(Long id) {
        long live = tickets.countLiveTicketsAttachedToProject(id);
        if (live > 0) {
            // Purging would take live entries with it — refuse; they must be deleted
            // (and then recovered or purged from the bin) explicitly first.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Project still has " + live + " live entries. Delete them first.");
        }
        List<Long> binned = tickets.findDeletedTicketIdsAttachedToProject(id);
        TicketDocumentService docs = documentServiceProvider == null
                ? null : documentServiceProvider.getIfAvailable();
        for (Long ticketId : binned) {
            tickets.purgeTicketDocuments(ticketId);
            tickets.purgeTicketResources(ticketId);
            tickets.purgeTicketFieldValues(ticketId);
            if (docs != null) docs.purgeTicketDirectory(ticketId);
        }
        tickets.purgeDeletedTicketsAttachedToProject(id);
        DepartmentService deptSvc = departmentServiceProvider.getObject();
        departments.findAllByProjectId(id).forEach(d -> deptSvc.deleteWithChildren(d.getId()));
        projects.purgeProjectMembers(id);
        projects.purgeProjectRow(id);
        audit.record(AuditService.Action.PURGE, AuditService.EntityType.PROJECT, id, null);
    }

    // ── Retention sweep ───────────────────────────────────────────────────────

    /**
     * Runs a few times a day (the window is "at least N days", never less) and purges
     * whatever has aged out. Each purge gets its own transaction — via the self-proxy,
     * the same pattern TicketService uses — so one stubborn row cannot roll back the
     * whole sweep.
     */
    @Scheduled(initialDelayString = "PT15M", fixedDelayString = "PT6H")
    public void sweepExpired() {
        RecycleBinService self = selfProvider == null ? this : selfProvider.getObject();
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int purgedTickets = 0;
        for (Long id : tickets.findExpiredDeletedTicketIds(cutoff)) {
            try {
                self.purgeTicketSwept(id);
                purgedTickets++;
            } catch (Exception e) {
                log.warn("Recycle-bin sweep: ticket #{} not purged: {}", id, e.toString());
            }
        }
        int purgedProjects = 0;
        for (Long id : projects.findExpiredDeletedProjectIds(cutoff)) {
            try {
                self.purgeProjectSwept(id);
                purgedProjects++;
            } catch (Exception e) {
                log.info("Recycle-bin sweep: project #{} kept for now ({}).", id, e.toString());
            }
        }
        if (purgedTickets > 0 || purgedProjects > 0) {
            log.info("Recycle-bin sweep: purged {} ticket(s) and {} project(s) older than {} days.",
                    purgedTickets, purgedProjects, retentionDays);
        }
    }

    /** Transactional wrappers for the sweeper (self-injected to cross the proxy). */
    @Transactional
    public void purgeTicketSwept(Long id) {
        purgeTicketInternal(id);
    }

    @Transactional
    public void purgeProjectSwept(Long id) {
        purgeProjectInternal(id);
    }
}