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

    public record ExtractedImageRef(
            @NotBlank @Size(max = 250) String name,
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String extractionId,
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

    public record ResourceRequest(
            @Size(max = 250) String name,
            @NotBlank @Size(max = 500) String url
    ) {}

    public record ArticleRequest(
            @Size(max = 500) String title,
            @Size(max = 2000000) String content,
            @Size(max = 250) String websiteName,
            @Size(max = 500) String websiteLink,
            @Valid List<ResourceRequest> resources,
            @Valid List<ExtractedImageRef> extractedImages
    ) {}

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
