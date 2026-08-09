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
            @NotBlank @Size(max = 500) String title,
            @NotBlank @Size(max = 200000) String content,
            @Size(max = 250) String websiteName,
            @Size(max = 500) String websiteLink,
            Map<String, String> customValues
    ) {}

    /** A single article within a bulk submission. */
    public record ArticleRequest(
            @NotBlank @Size(max = 500) String title,
            @NotBlank @Size(max = 200000) String content,
            @Size(max = 250) String websiteName,
            @Size(max = 500) String websiteLink
    ) {}

    /** Bulk-create request: shared metadata + N articles, each becoming its own Ticket. */
    public record BulkCreateRequest(
            @NotNull Long departmentId,
            @NotNull Long subcategoryId,
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
            String value
    ) {}

    public record TicketResponse(
            Long id,
            Long departmentId,
            String departmentName,
            Long subcategoryId,
            String subcategoryName,
            String title,
            String content,
            String websiteName,
            String websiteLink,
            String status,
            Instant submittedAt,
            Long submittedById,
            String submittedByUsername,
            String submittedByDisplayName,
            List<CustomValueResponse> customValues
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
