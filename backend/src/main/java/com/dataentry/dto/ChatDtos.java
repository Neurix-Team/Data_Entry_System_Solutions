package com.dataentry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public class ChatDtos {

    public record ChatRequest(
            @NotBlank @Size(max = 2000) String message,
            String currentPath,
            String lang
    ) {}

    public record ChatAction(
            String type,
            String path,
            String label
    ) {}

    public record ChatResponse(
            String reply,
            List<ChatAction> actions
    ) {}
}
