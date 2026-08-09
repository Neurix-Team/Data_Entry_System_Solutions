package com.dataentry.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public class DashboardDtos {

    public record DailyCount(LocalDate date, long count) {}

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
