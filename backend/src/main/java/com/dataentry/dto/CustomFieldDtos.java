package com.dataentry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CustomFieldDtos {

    public record UpsertFieldRequest(
            @NotNull Long subcategoryId,

            @NotBlank @Size(max = 100)
            @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]*$", message = "fieldKey must be a valid identifier")
            String fieldKey,

            @NotBlank @Size(max = 150) String label,

            @NotBlank
            @Pattern(regexp = "TEXT|TEXTAREA|NUMBER|URL|EMAIL|DATE|SELECT")
            String type,

            Boolean required,
            Integer displayOrder,
            String options,
            @Size(max = 250) String placeholder,
            Boolean active
    ) {}

    public record FieldResponse(
            Long id,
            Long subcategoryId,
            String subcategoryName,
            Long departmentId,
            String departmentName,
            String fieldKey,
            String label,
            String labelEn,
            String labelAr,
            String type,
            boolean required,
            int displayOrder,
            String options,
            String optionsEn,
            String optionsAr,
            String placeholder,
            String placeholderEn,
            String placeholderAr,
            boolean active
    ) {}
}
