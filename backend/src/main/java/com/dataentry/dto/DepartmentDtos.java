package com.dataentry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class DepartmentDtos {

    public record UpsertDepartmentRequest(
            @NotBlank @Size(max = 150) String name,
            Boolean active
    ) {}

    /**
     * `name` is already localized for the caller (based on Accept-Language). `nameEn` and `nameAr`
     * are the raw stored translations — exposed so admin screens can show both sides when editing.
     */
    public record DepartmentResponse(
            Long id,
            String name,
            String nameEn,
            String nameAr,
            boolean active
    ) {}
}
