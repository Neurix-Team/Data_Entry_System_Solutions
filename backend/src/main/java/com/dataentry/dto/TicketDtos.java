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
            Long departmentId,
            Long subcategoryId,
            Long projectId,
            @NotBlank @Size(max = 500) String title,
            @NotBlank @Size(max = 2000000) String content,
            @Size(max = 250) String websiteName,
            @Size(max = 500) String websiteLink,
            @Valid List<ResourceRequest> resources,
            @Valid List<ExtractedImageRef> extractedImages,
            Map<String, String> customValues
    ) {}

    /**
     * A reference to an image the server previously wrote into the extractions staging
     * area. On submit the server moves the file into the ticket's attachments and creates
     * a matching TicketDocument row; the file then follows the same lifecycle as any other
     * ticket attachment. The client-visible {@code name} is stored on that document row.
     */
    public record ExtractedImageRef(
            @NotBlank @Size(max = 250) String name,
            @NotBlank @Size(max = 64) String extractionId,
            @NotBlank @Size(max = 120)
            @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                    message = "Extracted image filename must contain only letters, digits, dot, dash or underscore")
            String filename
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

    /** A single article within a bulk submission. Title and content are optional so a user
     *  can submit an attachments-only article (files/extracted images with no written body).
     *  The frontend enforces "at least one of title+content OR attachments" before calling. */
    public record ArticleRequest(
            @Size(max = 500) String title,
            @Size(max = 2000000) String content,
            @Size(max = 250) String websiteName,
            @Size(max = 500) String websiteLink,
            @Valid List<ResourceRequest> resources,
            @Valid List<ExtractedImageRef> extractedImages
    ) {}

    /** Bulk-create request: shared metadata + N articles, each becoming its own Ticket.
     *  {@code departmentId} and {@code subcategoryId} are both optional — when both are
     *  omitted the ticket is filed against the first active department in the picked
     *  project so a user with a single scoped project doesn't have to touch either picker. */
    public record BulkCreateRequest(
            Long departmentId,
            Long subcategoryId,
            Long projectId,
            @NotEmpty @Valid List<ArticleRequest> articles,
            Map<String, String> customValues
    ) {}

    public record UpdateStatusRequest(
            @NotBlank
            @Pattern(regexp = "IN_PROGRESS|REVIEW|COMPLETED")
            String status
    ) {}

    /**
     * Admin edit of an entry's authored fields. Resources are replaced wholesale when the
     * list is present (null leaves them untouched). Department, subcategory, custom values
     * and attachments have their own flows and are not part of this request.
     */
    public record UpdateTicketRequest(
            @Size(max = 500) String title,
            @Size(max = 2000000) String content,
            @Size(max = 250) String websiteName,
            @Size(max = 500) String websiteLink,
            @Valid List<ResourceRequest> resources
    ) {}

    public record BulkApproveRequest(
            @NotEmpty @Size(max = 500) List<@NotNull Long> ticketIds
    ) {}

    public record BulkApproveResponse(
            int approved,
            List<TicketResponse> tickets
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
