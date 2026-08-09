package com.dataentry.dto;

import com.dataentry.model.TicketStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public class DashboardDtos {

    public record DailyCount(LocalDate date, long count) {}

    // --- typed JPQL projections (replace Object[] tuples) ---

    public record DepartmentCount(Long departmentId, long total) {}

    public record SubcategoryCount(Long subcategoryId, long total) {}

    public record DepartmentStatusCount(Long departmentId, TicketStatus status, long total) {}

    public record SubcategoryStatusCount(Long subcategoryId, TicketStatus status, long total) {}

    public record StatusCount(TicketStatus status, long total) {}

    public record DepartmentSubmission(Long departmentId, Instant submittedAt) {}

    public record SubcategorySubmission(Long subcategoryId, Instant submittedAt) {}

    public record LeaderboardRowRaw(Long userId, String displayName, String username, long total) {}

    public record UserCount(Long userId, long total) {}

    public record UserBreakdownRaw(Long groupId, String groupName, long total) {}

    // --- output DTOs (replace Map<String, Object>) ---

    public record AdminStats(
            long totalTickets,
            long totalDepartments,
            long activeFields,
            long totalUsers,
            long inProgress,
            long review,
            long completed,
            long completedToday
    ) {}

    public record TopPerformer(
            Long userId,
            String username,
            String displayName,
            long completed
    ) {}

    public record ReportData(
            Map<String, Long> byDay,
            List<TopPerformer> topPerformers,
            long completedThisWeek
    ) {}

    public record DomainStats(
            Long departmentId,
            String departmentName,
            long totalTickets,
            long subcategoryCount,
            long activeAgents,
            Map<String, Long> byStatus,
            List<DailyCount> last7Days
    ) {}

    public record SubcategoryStats(
            Long subcategoryId,
            String subcategoryName,
            Long departmentId,
            String departmentName,
            long totalTickets,
            Map<String, Long> byStatus,
            List<DailyCount> last7Days
    ) {}

    public record DomainDetail(
            Long departmentId,
            String departmentName,
            long totalTickets,
            long activeAgents,
            Map<String, Long> byStatus,
            List<DailyCount> last30Days,
            List<SubcategoryStats> subcategories
    ) {}

    public record AgentLeaderboardRow(
            Long userId,
            String username,
            String displayName,
            long totalTickets,
            long todayCount,
            long last7DaysCount,
            double avgPerDay
    ) {}

    public record LeaderboardResponse(
            String range,
            long activeAgents,
            List<AgentLeaderboardRow> rows
    ) {}

    public record UserBreakdownRow(Long id, String name, long count) {}

    public record UserActivity(
            Long userId,
            String username,
            String displayName,
            long totalTickets,
            int daysWindow,
            List<DailyCount> daily,
            List<UserBreakdownRow> byDepartment,
            List<UserBreakdownRow> bySubcategory,
            Map<String, Long> byStatus
    ) {}
}
