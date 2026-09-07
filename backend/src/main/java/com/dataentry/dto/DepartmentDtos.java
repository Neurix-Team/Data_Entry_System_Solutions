package com.dataentry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class DepartmentDtos {

    public record UpsertDepartmentRequest(
            @NotBlank @Size(max = 150) String name,
            @NotNull Long projectId,
            Boolean active
    ) {}

    public record DepartmentResponse(
            Long id,
            String name,
            String nameEn,
            String nameAr,
            boolean active,
            Long projectId,
            String projectName
    ) {}
}
