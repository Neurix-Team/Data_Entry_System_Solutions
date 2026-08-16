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

    /**
     * `name` is already localized for the caller (based on Accept-Language). `nameEn` and `nameAr`
     * are the raw stored translations — exposed so admin screens can show both sides when editing.
     * {@code projectId} / {@code projectName} tell the admin UI which project owns this department.
     */
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
