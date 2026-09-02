package com.dataentry.dto;

import java.time.Instant;
import java.util.List;

/**
 * Wire types for the super-admin data explorer + the /api/v1/export/tickets endpoint.
 * Both surfaces reuse the same {@link Row} shape so the frontend and external consumers
 * see identical JSON.
 */
public class DataExplorerDtos {

    /** Uploaded file metadata. Download via /api/tickets/{ticketId}/documents/{id} (session)
     *  or /api/v1/export/documents/{id}/download (token). */
    public record DocumentSummary(
            Long id,
            String name,
            String originalFilename,
            String contentType,
            long sizeBytes,
            /** SHA-256 hex of the raw bytes. Lets a downstream mirror detect that the same
             *  file id changed on the server, without re-downloading the payload. */
            String contentHash,
            Instant uploadedAt,
            /** Download URL relative to the API root. Populated on the export surface only. */
            String downloadUrl
    ) {}

    public record FieldValue(
            Long fieldId,
            String fieldName,
            String value
    ) {}

    /** A single ticket flattened for either the super-admin table or an AI ingest job. */
    public record Row(
            Long id,
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
            List<DocumentSummary> documents,
            List<FieldValue> customFields
    ) {}

    /** Envelope for paginated results. {@code nextCursor} is the id of the last row when
     *  more may follow; null when no more pages. */
    public record Page(
            List<Row> items,
            Long nextCursor,
            boolean hasMore,
            long total
    ) {}

    /** Compact filter-facet data for the explorer sidebar. */
    public record Facets(
            List<Named> teams,
            List<Named> projects,
            List<Named> users
    ) {}

    public record Named(Long id, String name) {}
}
