package com.dataentry.service;

import com.dataentry.dto.DashboardDtos;
import com.dataentry.model.Role;
import com.dataentry.model.TicketStatus;
import com.dataentry.repository.CustomFieldRepository;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.SubcategoryRepository;
import com.dataentry.repository.TicketRepository;
import com.dataentry.repository.UserRepository;
import com.dataentry.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
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
    @Mock TicketRepository.AdminStatsProjection adminStatsProjection;
    @Mock TicketRepository.WeeklySummaryProjection weeklyDayOne;
    @Mock TicketRepository.WeeklySummaryProjection weeklyDayTwo;

    /** Fixed clock: 2026-08-09 12:00 UTC — makes every date-based test deterministic. */
    private final Clock fixedClock = Clock.fixed(
            LocalDate.of(2026, 8, 9).atStartOfDay(ZoneOffset.UTC).toInstant(),
            ZoneOffset.UTC);

    private DashboardService newService() {
        return new DashboardService(
                fixedClock, ticketRepository, departmentRepository,
                subcategoryRepository, customFieldRepository, userRepository, new Localizer());
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void adminStats_aggregatesUsingRepositoryCounts() {
        TenantContext.set(1L, Role.ADMIN, 10L, null);
        when(ticketRepository.aggregateAdminStats(eq(1L), any(Instant.class)))
                .thenReturn(adminStatsProjection);
        when(adminStatsProjection.getTotalTickets()).thenReturn(42L);
        when(adminStatsProjection.getTotalDepartments()).thenReturn(3L);
        when(adminStatsProjection.getActiveFields()).thenReturn(7L);
        when(adminStatsProjection.getTotalUsers()).thenReturn(11L);
        when(adminStatsProjection.getInProgress()).thenReturn(10L);
        when(adminStatsProjection.getReview()).thenReturn(5L);
        when(adminStatsProjection.getCompleted()).thenReturn(27L);
        when(adminStatsProjection.getCompletedToday()).thenReturn(4L);

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
        TenantContext.set(1L, Role.ADMIN, 10L, null);
        when(weeklyDayOne.getDay()).thenReturn(LocalDate.of(2026, 8, 5));
        when(weeklyDayOne.getTotal()).thenReturn(2L);
        when(weeklyDayOne.getCompleted()).thenReturn(1L);
        when(weeklyDayTwo.getDay()).thenReturn(LocalDate.of(2026, 8, 9));
        when(weeklyDayTwo.getTotal()).thenReturn(1L);
        when(weeklyDayTwo.getCompleted()).thenReturn(1L);
        when(ticketRepository.weeklySummary(eq(1L), any(Instant.class), eq("Z")))
                .thenReturn(List.of(weeklyDayOne, weeklyDayTwo));
        when(ticketRepository.topPerformersByStatus(eq(TicketStatus.COMPLETED), any(Pageable.class)))
                .thenReturn(List.of(new DashboardDtos.TopPerformer(1L, "alice", "Alice", 5L)));

        DashboardDtos.ReportData report = newService().report();

        assertThat(report.byDay()).hasSize(7);
        assertThat(report.byDay().get("2026-08-05")).isEqualTo(2L);
        assertThat(report.byDay().get("2026-08-09")).isEqualTo(1L);
        assertThat(report.byDay().get("2026-08-03")).isEqualTo(0L);
        assertThat(report.completedThisWeek()).isEqualTo(2);
        assertThat(report.topPerformers()).hasSize(1);
        assertThat(report.topPerformers().get(0).username()).isEqualTo("alice");
    }

    @Test
    void leaderboard_normalizesUnknownRangeToWeek() {
        TenantContext.set(1L, Role.ADMIN, 10L, null);
        when(ticketRepository.leaderboardAggregate(eq(1L), any(Instant.class), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        DashboardDtos.LeaderboardResponse res = newService().leaderboard("garbage-value");
        assertThat(res.range()).isEqualTo("week");
    }
}
