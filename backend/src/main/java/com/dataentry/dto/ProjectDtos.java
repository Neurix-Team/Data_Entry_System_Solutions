package com.dataentry.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public class ProjectDtos {

    public record UpsertProjectRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 250) String subtitle,
            @NotNull Long departmentId,
            Set<Long> memberIds,
            LocalDate startDate,
            LocalDate endDate,
            @Min(0) @Max(100) Integer progress,
            @Pattern(regexp = "ON_TRACK|DELAYED|COMPLETED")
            String status
    ) {}

    public record ProjectMember(
            Long id,
            String username,
            String displayName
    ) {}

    public record ProjectResponse(
            Long id,
            String name,
            String subtitle,
            Long departmentId,
            String departmentName,
            List<ProjectMember> members,
            LocalDate startDate,
            LocalDate endDate,
            Integer daysLeft,        // negative = overdue, positive = days remaining, null = completed / no end
            int progress,
            String status
    ) {}
}
