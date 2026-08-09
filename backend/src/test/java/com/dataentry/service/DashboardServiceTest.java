package com.dataentry.service;

import com.dataentry.dto.DashboardDtos;
import com.dataentry.model.TicketStatus;
import com.dataentry.repository.CustomFieldRepository;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.SubcategoryRepository;
import com.dataentry.repository.TicketRepository;
import com.dataentry.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock TicketRepository ticketRepository;
    @Mock DepartmentRepository departmentRepository;
    @Mock SubcategoryRepository subcategoryRepository;
    @Mock CustomFieldRepository customFieldRepository;
    @Mock UserRepository userRepository;

    /** Fixed clock: 2026-08-09 12:00 UTC — makes every date-based test deterministic. */
    private final Clock fixedClock = Clock.fixed(
            LocalDate.of(2026, 8, 9).atStartOfDay(ZoneOffset.UTC).toInstant(),
            ZoneOffset.UTC);

    private DashboardService newService() {
        return new DashboardService(
                fixedClock, ticketRepository, departmentRepository,
                subcategoryRepository, customFieldRepository, userRepository, new Localizer());
    }

    @Test
    void adminStats_aggregatesUsingRepositoryCounts() {
        when(ticketRepository.count()).thenReturn(42L);
        when(departmentRepository.count()).thenReturn(3L);
        when(customFieldRepository.countByActiveTrue()).thenReturn(7L);
        when(userRepository.count()).thenReturn(11L);
        when(ticketRepository.countByStatus(TicketStatus.IN_PROGRESS)).thenReturn(10L);
        when(ticketRepository.countByStatus(TicketStatus.REVIEW)).thenReturn(5L);
        when(ticketRepository.countByStatus(TicketStatus.COMPLETED)).thenReturn(27L);
        when(ticketRepository.countByStatusAndSubmittedAtGreaterThanEqual(eq(TicketStatus.COMPLETED), any(Instant.class)))
                .thenReturn(4L);

        DashboardDtos.AdminStats stats = newService().adminStats();

        assertThat(stats.totalTickets()).isEqualTo(42);
        assertThat(stats.totalDepartments()).isEqualTo(3);
        assertThat(stats.activeFields()).isEqualTo(7);
        assertThat(stats.totalUsers()).isEqualTo(11);
        assertThat(stats.inProgress()).isEqualTo(10);
        assertThat(stats.review()).isEqualTo(5);
        assertThat(stats.completed()).isEqualTo(27);
        assertThat(stats.completedToday()).isEqualTo(4);
    }

    @Test
    void report_bucketsSubmissionsAcrossSevenDays() {
        // Today is 2026-08-09 → week window is 2026-08-03 .. 2026-08-09
        Instant t = LocalDate.of(2026, 8, 5).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant t2 = LocalDate.of(2026, 8, 5).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
        Instant t3 = LocalDate.of(2026, 8, 9).atStartOfDay(ZoneOffset.UTC).toInstant();

        when(ticketRepository.submissionTimesSince(any(Instant.class)))
                .thenReturn(List.of(t, t2, t3));
        when(ticketRepository.topPerformersByStatus(eq(TicketStatus.COMPLETED), any(Pageable.class)))
                .thenReturn(List.of(new DashboardDtos.TopPerformer(1L, "alice", "Alice", 5L)));
        when(ticketRepository.countByStatusAndSubmittedAtGreaterThanEqual(eq(TicketStatus.COMPLETED), any(Instant.class)))
                .thenReturn(9L);

        DashboardDtos.ReportData report = newService().report();

        assertThat(report.byDay()).hasSize(7);
        assertThat(report.byDay().get("2026-08-05")).isEqualTo(2L);
        assertThat(report.byDay().get("2026-08-09")).isEqualTo(1L);
        assertThat(report.byDay().get("2026-08-03")).isEqualTo(0L);
        assertThat(report.completedThisWeek()).isEqualTo(9);
        assertThat(report.topPerformers()).hasSize(1);
        assertThat(report.topPerformers().get(0).username()).isEqualTo("alice");
    }

    @Test
    void leaderboard_normalizesUnknownRangeToWeek() {
        when(ticketRepository.leaderboardSince(any(Instant.class))).thenReturn(List.of());
        when(ticketRepository.distinctAgentsSince(any(Instant.class))).thenReturn(0L);
        when(ticketRepository.countByUserSince(any(Instant.class))).thenReturn(List.of());

        DashboardDtos.LeaderboardResponse res = newService().leaderboard("garbage-value");
        assertThat(res.range()).isEqualTo("week");
    }
}
