package com.dataentry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public class AiCheckDtos {

    public record CheckRequest(
            @NotBlank @Size(max = 20000) String content
    ) {}

    public record CheckResponse(
            String original,
            String corrected,
            List<String> notes
    ) {}
}
