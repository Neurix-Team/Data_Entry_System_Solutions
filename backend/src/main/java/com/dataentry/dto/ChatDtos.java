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

    /** Suggested action attached to the assistant's reply. */
    public record ChatAction(
            String type,   // "navigate"
            String path,   // e.g. "/admin/users"
            String label   // localized button label
    ) {}

    public record ChatResponse(
            String reply,
            List<ChatAction> actions
    ) {}
}
