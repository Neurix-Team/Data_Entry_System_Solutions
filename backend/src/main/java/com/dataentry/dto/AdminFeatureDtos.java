package com.dataentry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Admin feature pack: announcements (C2), workload balancing (C3), weekly report
 * (C4), per-agent data quality (C5). CSV import (C1) result shapes live here too.
 */
public class AdminFeatureDtos {

    // ── C2: announcements ─────────────────────────────────────────────────────

    public record CreateAnnouncementRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 4000) String body,
            @NotBlank @Pattern(regexp = "ALL|USERS|ADMINS") String audience
    ) {}

    public record AnnouncementResponse(
            Long id, String title, String body, String audience, Instant createdAt,
            String createdByName
    ) {}

    // ── C3: workload balancing ─────────────────────────────────────────────────

    public record WorkloadRow(
            Long userId, String displayName, String username,
            long openAssignments, long weekEntries, long weekCompleted,
            /** Heuristic: openAssignments weigh 3x a fresh entry — both are live work. */
            long loadScore
    ) {}

    public record WorkloadResponse(List<WorkloadRow> rows, Long busiestUserId, Long freestUserId) {}

    // ── C4: weekly report ──────────────────────────────────────────────────────

    public record WeeklyDayRow(String day, long total, long completed) {}

    public record WeeklyPerformer(Long userId, String displayName, long total) {}

    public record WeeklyReport(
            Instant from, Instant to,
            long totalEntries, long completedEntries, long completionRatePct,
            List<WeeklyDayRow> byDay, List<WeeklyPerformer> topPerformers
    ) {}

    public record DispatchResult(int notified) {}

    // ── C5: per-agent data quality ─────────────────────────────────────────────

    public record QualityRow(
            Long userId, String displayName, String username,
            long total, long completed, long review, long inProgress,
            double completedRate, double reviewRate, long weekTotal
    ) {}

    public record QualityResponse(List<QualityRow> rows) {}

    // ── C1: CSV import ─────────────────────────────────────────────────────────

    public record ImportRowError(int row, String reason) {}

    public record ImportResult(int created, int failed, int skippedHeader,
                               List<ImportRowError> errors) {}
}