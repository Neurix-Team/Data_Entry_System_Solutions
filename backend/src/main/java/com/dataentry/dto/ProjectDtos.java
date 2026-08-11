package com.dataentry.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public class ProjectDtos {

    /** Create or update payload. departmentIds is required (min 1) — a project must own at
     *  least one department. The old single-departmentId field stays optional for backwards
     *  compatibility with older frontend versions; when both are set, departmentIds wins. */
    public record UpsertProjectRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 250) String subtitle,
            @NotEmpty(message = "At least one department is required") List<Long> departmentIds,
            /** Legacy single-department pointer — accepted but ignored when departmentIds is present. */
            Long departmentId,
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
            String displayName,
            String displayNameEn,
            String displayNameAr
    ) {}

    /** Compact department reference embedded in a ProjectResponse. */
    public record ProjectDepartment(
            Long id,
            String name,
            String nameEn,
            String nameAr
    ) {}

    public record ProjectResponse(
            Long id,
            String name,
            String nameEn,
            String nameAr,
            String subtitle,
            String subtitleEn,
            String subtitleAr,
            /** Legacy primary-department pointer — kept in the response for backward-compat. */
            Long departmentId,
            String departmentName,
            String departmentNameEn,
            String departmentNameAr,
            /** All departments assigned to this project — the new source of truth. */
            List<ProjectDepartment> departments,
            List<ProjectMember> members,
            LocalDate startDate,
            LocalDate endDate,
            Integer daysLeft,        // negative = overdue, positive = days remaining, null = completed / no end
            int progress,
            String status
    ) {}
}
