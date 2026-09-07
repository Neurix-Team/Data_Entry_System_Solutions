package com.dataentry.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public class ProjectDtos {

    public record UpsertProjectRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 250) String subtitle,
            List<Long> departmentIds,
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
            Long departmentId,
            String departmentName,
            String departmentNameEn,
            String departmentNameAr,
            List<ProjectDepartment> departments,
            List<ProjectMember> members,
            LocalDate startDate,
            LocalDate endDate,
            Integer daysLeft,
            int progress,
            String status
    ) {}
}
