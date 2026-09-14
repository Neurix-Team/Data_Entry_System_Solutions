package com.dataentry.service;

import com.dataentry.dto.AssignmentDtos;
import com.dataentry.model.Assignment;
import com.dataentry.model.AssignmentStatus;
import com.dataentry.model.Role;
import com.dataentry.model.Team;
import com.dataentry.model.User;
import com.dataentry.repository.AssignmentRepository;
import com.dataentry.repository.UserRepository;
import com.dataentry.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssignmentServiceTest {

    @Mock AssignmentRepository assignments;
    @Mock UserRepository users;
    @Mock NotificationService notifications;
    @Mock AuditService audit;
    @Mock Localizer localizer;

    private AssignmentService service;

    private final Team team = Team.builder().id(1L).build();
    private final Team otherTeam = Team.builder().id(2L).build();
    private User leader;
    private User agent;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L, Role.ADMIN, 10L, null);
        leader = User.builder().id(10L).team(team).username("lead").role(Role.ADMIN).active(true).build();
        agent = User.builder().id(20L).team(team).username("agent").displayName("Agent One")
                .role(Role.USER).active(true).build();
        lenient().when(localizer.currentLang()).thenReturn(TranslationService.Lang.EN);
        lenient().when(localizer.pick(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        lenient().when(assignments.save(any(Assignment.class))).thenAnswer(inv -> {
            Assignment a = inv.getArgument(0);
            if (a.getId() == null) a.setId(99L);
            return a;
        });
        service = new AssignmentService(assignments, users, notifications, audit, localizer);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void create_stampsLeaderAndNotifiesAssignee() {
        when(users.findById(20L)).thenReturn(Optional.of(agent));

        AssignmentDtos.Response r = service.create(leader,
                new AssignmentDtos.CreateRequest(20L, "  Index the March folder ", "  ", LocalDate.of(2026, 9, 20)));

        assertThat(r.id()).isEqualTo(99L);
        assertThat(r.title()).isEqualTo("Index the March folder");
        assertThat(r.description()).isNull();
        assertThat(r.status()).isEqualTo("OPEN");
        assertThat(r.assignee().id()).isEqualTo(20L);
        assertThat(r.assignedBy().id()).isEqualTo(10L);
        assertThat(r.dueDate()).isEqualTo(LocalDate.of(2026, 9, 20));

        verify(notifications).emit(eq(agent), eq(AssignmentService.NOTIFY_CREATED),
                anyString(), eq(AssignmentService.REF_TYPE), eq(99L), isNull());
    }

    @Test
    void create_rejectsAssigneeFromAnotherTeam() {
        User stranger = User.builder().id(30L).team(otherTeam).username("x").role(Role.USER).active(true).build();
        when(users.findById(30L)).thenReturn(Optional.of(stranger));

        assertThatThrownBy(() -> service.create(leader,
                new AssignmentDtos.CreateRequest(30L, "Anything", null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(assignments, never()).save(any());
    }

    @Test
    void create_rejectsDisabledAssignee() {
        agent.setActive(false);
        when(users.findById(20L)).thenReturn(Optional.of(agent));

        assertThatThrownBy(() -> service.create(leader,
                new AssignmentDtos.CreateRequest(20L, "Anything", null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void markDone_flipsStatusAndNotifiesLeader() {
        TenantContext.set(1L, Role.USER, 20L, null);
        Assignment a = Assignment.builder().id(5L).team(team).title("Scan box 4")
                .assignee(agent).assignedBy(leader).status(AssignmentStatus.OPEN).build();
        when(assignments.findWithPeopleById(5L)).thenReturn(Optional.of(a));

        AssignmentDtos.Response r = service.markDone(5L, agent);

        assertThat(r.status()).isEqualTo("DONE");
        assertThat(r.completedAt()).isNotNull();
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(notifications).emit(eq(leader), eq(AssignmentService.NOTIFY_DONE),
                msg.capture(), eq(AssignmentService.REF_TYPE), eq(5L), isNull());
        assertThat(msg.getValue()).contains("Agent One").contains("Scan box 4");
    }

    @Test
    void markDone_isIdempotent() {
        TenantContext.set(1L, Role.USER, 20L, null);
        Assignment a = Assignment.builder().id(5L).team(team).title("t")
                .assignee(agent).assignedBy(leader).status(AssignmentStatus.DONE).build();
        when(assignments.findWithPeopleById(5L)).thenReturn(Optional.of(a));

        service.markDone(5L, agent);

        verify(assignments, never()).save(any());
        verify(notifications, never()).emit(any(), anyString(), anyString(), any(), anyLong(), any());
    }

    @Test
    void markDone_bySomeoneElseIsNotFound() {
        TenantContext.set(1L, Role.USER, 21L, null);
        User other = User.builder().id(21L).team(team).username("other").role(Role.USER).active(true).build();
        Assignment a = Assignment.builder().id(5L).team(team).title("t")
                .assignee(agent).assignedBy(leader).status(AssignmentStatus.OPEN).build();
        when(assignments.findWithPeopleById(5L)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.markDone(5L, other))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(a.getStatus()).isEqualTo(AssignmentStatus.OPEN);
    }

    @Test
    void leaderCannotTouchAnotherTeamsAssignment() {
        Assignment a = Assignment.builder().id(5L).team(otherTeam).title("t")
                .assignee(agent).assignedBy(leader).status(AssignmentStatus.DONE).build();
        when(assignments.findWithPeopleById(5L)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.reopenByLeader(5L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> service.delete(5L))
                .isInstanceOf(ResponseStatusException.class);
        verify(assignments, never()).delete(any(Assignment.class));
    }

    @Test
    void reopen_clearsCompletion() {
        Assignment a = Assignment.builder().id(5L).team(team).title("t")
                .assignee(agent).assignedBy(leader).status(AssignmentStatus.DONE)
                .completedAt(java.time.Instant.now()).build();
        when(assignments.findWithPeopleById(5L)).thenReturn(Optional.of(a));

        AssignmentDtos.Response r = service.reopenByLeader(5L);

        assertThat(r.status()).isEqualTo("OPEN");
        assertThat(r.completedAt()).isNull();
    }

    @Test
    void update_reassignNotifiesNewPersonOnly() {
        User second = User.builder().id(22L).team(team).username("second").role(Role.USER).active(true).build();
        Assignment a = Assignment.builder().id(5L).team(team).title("t")
                .assignee(agent).assignedBy(leader).status(AssignmentStatus.OPEN).build();
        when(assignments.findWithPeopleById(5L)).thenReturn(Optional.of(a));
        when(users.findById(22L)).thenReturn(Optional.of(second));

        AssignmentDtos.Response r = service.update(5L,
                new AssignmentDtos.UpdateRequest(22L, "Renamed", null, null, Boolean.TRUE));

        assertThat(r.title()).isEqualTo("Renamed");
        assertThat(r.assignee().id()).isEqualTo(22L);
        assertThat(r.dueDate()).isNull();
        verify(notifications).emit(eq(second), eq(AssignmentService.NOTIFY_CREATED),
                anyString(), eq(AssignmentService.REF_TYPE), eq(5L), isNull());
        verify(notifications, never()).emit(eq(agent), anyString(), anyString(), any(), anyLong(), any());
    }
}
