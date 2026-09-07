package com.dataentry.dto;

import com.dataentry.model.UploadTarget;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public class UploadSessionDtos {

    public record CreateRequest(
            @NotBlank @Size(max = 300) String filename,
            @Positive long size,
            @Size(max = 200) String contentType,
            @NotNull UploadTarget target,
            Long projectId,
            Long departmentId,
            Long ticketId,
            @Size(max = 250) String title
    ) {}

    public record SessionResponse(
            String id,
            String filename,
            long size,
            int chunkBytes,
            int totalChunks,
            List<Integer> received,
            Instant expiresAt
    ) {}

    public record ChunkAck(int index, long bytes) {}

    public record CompleteResponse(
            UploadTarget target,
            TicketDtos.TicketResponse ticket,
            TicketDtos.DocumentResponse document
    ) {}
}
