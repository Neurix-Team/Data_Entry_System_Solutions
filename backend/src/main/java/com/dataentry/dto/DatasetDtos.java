package com.dataentry.dto;

import java.time.Instant;
import java.util.List;

public class DatasetDtos {
    public record Row(
            Long id,
            Long sourceTicketId,
            Long teamId,
            String teamName,
            Long projectId,
            String projectName,
            Long departmentId,
            String departmentName,
            Long subcategoryId,
            String subcategoryName,
            Long submittedByUserId,
            String submittedByUsername,
            String submittedByDisplayName,
            String submittedByEmail,
            String submittedByPhone,
            String submittedByRole,
            String title,
            String content,
            String websiteName,
            String websiteLink,
            String status,
            Instant submittedAt,
            List<DataExplorerDtos.DocumentSummary> attachments,
            List<DataExplorerDtos.FieldValue> customFields,
            Instant publishedAt,
            Instant refreshedAt
    ) {}

    public record Page(List<Row> items, Long nextCursor, boolean hasMore, long total) {}

    public record PublishResult(int scanned, int inserted, int updated, int unchanged, long total) {}
}
