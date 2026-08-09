package com.dataentry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class SubcategoryDtos {

    public record UpsertSubcategoryRequest(
            @NotNull Long departmentId,
            @NotBlank @Size(max = 150) String name,
            Boolean active
    ) {}

    public record SubcategoryResponse(
            Long id,
            Long departmentId,
            String departmentName,
            String name,
            boolean active,
            long ticketCount,
            long fieldCount
    ) {}
}
