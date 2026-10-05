package com.dataentry.dto;

import java.time.Instant;
import java.util.List;

public class DataExplorerDtos {

    public record DocumentSummary(
            Long id,
            String name,
            String originalFilename,
            String contentType,
            long sizeBytes,
            String contentHash,
            Instant uploadedAt,
            String downloadUrl
    ) {}

    public record FieldValue(
            Long fieldId,
            String fieldName,
            String value
    ) {}

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

    public record Stats(long totalTickets, long ticketsWithFiles, long totalFiles, long totalBytes,
                        long pdfFiles, long wordFiles, long spreadsheetFiles, long imageFiles,
                        long presentationFiles, long otherFiles) {}

    public record Page(
            List<Row> items,
            Long nextCursor,
            boolean hasMore,
            long total
    ) {}

    public record Facets(
            List<Named> teams,
            List<Named> projects,
            List<Named> users,
            List<DepartmentNamed> departments
    ) {}

    public record Named(Long id, String name) {}
    public record DepartmentNamed(Long id, String name, Long projectId, Long teamId) {}

    public record ManifestEntry(
            Long ticketId,
            String ticketTitle,
            Instant submittedAt,
            Long teamId,
            String teamName,
            Long projectId,
            String projectName,
            Long departmentId,
            String departmentName,
            Long subcategoryId,
            String subcategoryName,
            String submittedBy,
            Long documentId,
            String name,
            String originalFilename,
            String contentType,
            long sizeBytes,
            String contentHash
    ) {}

    public record ManifestTicket(
            Long id,
            String title,
            String content,
            String websiteName,
            String websiteLink,
            String status,
            Instant submittedAt,
            String submittedBy,
            String teamName,
            String projectName,
            String departmentName,
            String subcategoryName,
            List<FieldValue> customFields
    ) {}

    public record Manifest(
            List<ManifestEntry> files,
            List<ManifestTicket> tickets,
            long totalFiles,
            long totalBytes,
            long totalTickets
    ) {}
}
