package com.dataentry.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class TicketDtos {

    public record CreateTicketRequest(
            @NotNull Long departmentId,
            @NotNull Long subcategoryId,
            Long projectId,
            @NotBlank @Size(max = 500) String title,
            @NotBlank @Size(max = 200000) String content,
            @Size(max = 250) String websiteName,
            @Size(max = 500) String websiteLink,
            @Valid List<ResourceRequest> resources,
            Map<String, String> customValues
    ) {}

    public record ResourceResponse(
            Long id,
            String name,
            String nameEn,
            String nameAr,
            String url,
            int displayOrder
    ) {}

    public record DocumentResponse(
            Long id,
            String name,
            String originalFilename,
            String contentType,
            long sizeBytes,
            Instant uploadedAt
    ) {}

    /** A single (name, url) resource attached to an article. */
    public record ResourceRequest(
            @Size(max = 250) String name,
            @NotBlank @Size(max = 500) String url
    ) {}

    /** A single article within a bulk submission. */
    public record ArticleRequest(
            @NotBlank @Size(max = 500) String title,
            @NotBlank @Size(max = 200000) String content,
            @Size(max = 250) String websiteName,
            @Size(max = 500) String websiteLink,
            @Valid List<ResourceRequest> resources
    ) {}

    /** Bulk-create request: shared metadata + N articles, each becoming its own Ticket. */
    public record BulkCreateRequest(
            @NotNull Long departmentId,
            @NotNull Long subcategoryId,
            Long projectId,
            @NotEmpty @Valid List<ArticleRequest> articles,
            Map<String, String> customValues
    ) {}

    public record UpdateStatusRequest(
            @NotBlank
            @Pattern(regexp = "IN_PROGRESS|REVIEW|COMPLETED")
            String status
    ) {}

    public record CustomValueResponse(
            Long fieldId,
            String fieldKey,
            String label,
            String labelEn,
            String labelAr,
            String value,
            String valueEn,
            String valueAr
    ) {}

    public record TicketResponse(
            Long id,
            Long departmentId,
            String departmentName,
            String departmentNameEn,
            String departmentNameAr,
            Long subcategoryId,
            String subcategoryName,
            String subcategoryNameEn,
            String subcategoryNameAr,
            Long projectId,
            String projectName,
            String title,
            String titleEn,
            String titleAr,
            String content,
            String contentEn,
            String contentAr,
            String websiteName,
            String websiteNameEn,
            String websiteNameAr,
            String websiteLink,
            String status,
            Instant submittedAt,
            Long submittedById,
            String submittedByUsername,
            String submittedByDisplayName,
            String submittedByDisplayNameEn,
            String submittedByDisplayNameAr,
            List<CustomValueResponse> customValues,
            List<ResourceResponse> resources,
            List<DocumentResponse> documents
    ) {}

    public record BulkCreateResponse(
            int created,
            List<TicketResponse> tickets
    ) {}

    public record TicketPage(
            List<TicketResponse> items,
            long totalItems,
            int totalPages,
            int page,
            int size
    ) {}
}
