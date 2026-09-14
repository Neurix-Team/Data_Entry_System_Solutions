package com.dataentry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public class AssignmentDtos {

    public record CreateRequest(
            @NotNull Long assigneeId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 20000) String description,
            LocalDate dueDate
    ) {}

    public record UpdateRequest(
            Long assigneeId,
            @Size(max = 200) String title,
            @Size(max = 20000) String description,
            LocalDate dueDate,
            /** true clears the due date; ignored when dueDate is also supplied. */
            Boolean clearDueDate
    ) {}

    public record Person(
            Long id,
            String username,
            String displayName,
            String displayNameEn,
            String displayNameAr,
            Instant avatarUpdatedAt
    ) {}

    public record Response(
            Long id,
            String title,
            String description,
            String status,
            LocalDate dueDate,
            Person assignee,
            Person assignedBy,
            Instant createdAt,
            Instant completedAt
    ) {}

    public record Summary(
            long open,
            long done
    ) {}

    public record ListResponse(
            List<Response> items,
            Summary summary
    ) {}
}
