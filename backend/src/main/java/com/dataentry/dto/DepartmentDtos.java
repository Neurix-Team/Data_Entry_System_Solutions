package com.dataentry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class DepartmentDtos {

    public record UpsertDepartmentRequest(
            @NotBlank @Size(max = 150) String name,
            Boolean active
    ) {}

    public record DepartmentResponse(
            Long id,
            String name,
            boolean active
    ) {}
}
