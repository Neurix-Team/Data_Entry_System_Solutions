package com.dataentry.service;

import com.dataentry.model.Role;
import com.dataentry.model.User;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.ProjectRepository;
import com.dataentry.repository.TicketRepository;
import com.dataentry.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Recycle bin rules as unit tests: restores stay tenant-scoped (404, not 403, for
 * foreign rows), users can only restore their own entries, and permanent purges
 * refuse to take live entries with them.
 */
@ExtendWith(MockitoExtension.class)
class RecycleBinServiceTest {

    @Mock TicketRepository tickets;
    @Mock ProjectRepository projects;
    @Mock DepartmentRepository departments;
    @Mock AuditService audit;
    @Mock ObjectProvider<DepartmentService> departmentProvider;
    @Mock ObjectProvider<TicketDocumentService> documentProvider;
    @Mock ObjectProvider<RecycleBinService> selfProvider;

    private User owner;
    private RecycleBinService service;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @BeforeEach
    void setup() {
        TenantContext.set(7L, Role.USER, 42L, null);
        owner = User.builder().id(42L).username("owner").role(Role.USER).active(true).build();
        service = new RecycleBinService(tickets, projects, departments, audit,
                departmentProvider, documentProvider, selfProvider, 30);
    }

    private TicketRepository.DeletedTicketRow ticketRow(long teamId, long submittedBy) {
        return new TicketRepository.DeletedTicketRow() {
            public Long getId() { return 5L; }
            public String getTitle() { return "Binned entry"; }
            public String getTitleEn() { return "Binned entry"; }
            public String getTitleAr() { return null; }
            public String getStatus() { return "IN_PROGRESS"; }
            public Long getSubmittedById() { return submittedBy; }
            public String getSubmittedByUsername() { return "owner"; }
            public Instant getDeletedAt() { return Instant.now().minusSeconds(3600); }
            public Long getDeletedById() { return 42L; }
            public String getDeletedByName() { return "owner"; }
            public Long getTeamId() { return teamId; }
        };
    }

    @Test
    void restoreOwnTicket_restoresAndAudits() {
        when(tickets.findDeletedTicketById(5L)).thenReturn(Optional.of(ticketRow(7L, 42L)));
        when(tickets.restoreTicket(5L)).thenReturn(1);

        service.restoreTicket(5L, owner, false);

        verify(tickets).restoreTicket(5L);
        verify(audit).record(AuditService.Action.RESTORE, AuditService.EntityType.TICKET, 5L, null);
    }

    @Test
    void restoreForeignUsersTicket_isRefused() {
        when(tickets.findDeletedTicketById(5L)).thenReturn(Optional.of(ticketRow(7L, 99L)));

        assertThatThrownBy(() -> service.restoreTicket(5L, owner, false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("only restore your own");
        verifyNoInteractions(audit);
    }

    @Test
    void restoreForeignTeamsTicket_is404Not403() {
        TenantContext.set(8L, Role.USER, 42L, null);
        when(tickets.findDeletedTicketById(5L)).thenReturn(Optional.of(ticketRow(7L, 42L)));

        assertThatThrownBy(() -> service.restoreTicket(5L, owner, false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        verifyNoInteractions(audit);
    }

    // ── purge rules ───────────────────────────────────────────────────────────

    @Test
    void purgeTicket_notInBin_is404() {
        when(tickets.countBinnedTicketById(5L)).thenReturn(0L);

        assertThatThrownBy(() -> service.purgeTicket(5L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        verifyNoInteractions(audit);
    }

    @Test
    void purgeTicket_inBin_removesChildrenAndAudits() {
        when(tickets.countBinnedTicketById(5L)).thenReturn(1L);
        when(tickets.teamIdOfAnyTicket(5L)).thenReturn(7L);

        service.purgeTicket(5L);

        verify(tickets).purgeTicketDocuments(5L);
        verify(tickets).purgeTicketResources(5L);
        verify(tickets).purgeTicketFieldValues(5L);
        verify(tickets).purgeTicketRow(5L);
        verify(audit).record(AuditService.Action.PURGE, AuditService.EntityType.TICKET, 5L, null);
    }

    @Test
    void purgeProjectWithLiveEntries_isRefused() {
        when(projects.countBinnedProjectById(3L)).thenReturn(1L);
        when(projects.teamIdOfAnyProject(3L)).thenReturn(7L);
        when(tickets.countLiveTicketsAttachedToProject(3L)).thenReturn(4L);

        assertThatThrownBy(() -> service.purgeProject(3L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("live entries");
        verifyNoInteractions(audit);
    }

    @Test
    void purgeProject_cascadesBinnedTicketsThenRemovesTheProject() {
        when(projects.countBinnedProjectById(3L)).thenReturn(1L);
        when(projects.teamIdOfAnyProject(3L)).thenReturn(7L);
        when(tickets.countLiveTicketsAttachedToProject(3L)).thenReturn(0L);
        when(tickets.findDeletedTicketIdsAttachedToProject(3L)).thenReturn(List.of(11L, 12L));
        when(departments.findAllByProjectId(3L)).thenReturn(List.of());

        service.purgeProject(3L);

        for (Long tid : List.of(11L, 12L)) {
            verify(tickets).purgeTicketDocuments(tid);
            verify(tickets).purgeTicketResources(tid);
            verify(tickets).purgeTicketFieldValues(tid);
        }
        verify(tickets).purgeDeletedTicketsAttachedToProject(3L);
        verify(projects).purgeProjectMembers(3L);
        verify(projects).purgeProjectRow(3L);
        verify(audit).record(AuditService.Action.PURGE, AuditService.EntityType.PROJECT, 3L, null);
    }

    @Test
    void restoreLostRace_is404() {
        // The row was read as binned, but the purge won between read and update.
        when(tickets.findDeletedTicketById(5L)).thenReturn(Optional.of(ticketRow(7L, 42L)));
        when(tickets.restoreTicket(5L)).thenReturn(0);

        assertThatThrownBy(() -> service.restoreTicket(5L, owner, false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        verifyNoInteractions(audit);
    }
}