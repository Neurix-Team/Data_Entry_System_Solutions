package com.dataentry.dto;

import com.dataentry.model.UploadTarget;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Wire shapes for the chunked, parallel, resumable upload flow (/api/uploads/sessions). */
public class UploadSessionDtos {

    /**
     * Opens a session. Everything the server needs to validate the upload up front
     * (size, extension, quota, target access) travels here, so a doomed upload is refused
     * before a single chunk is sent instead of after hundreds of megabytes.
     */
    public record CreateRequest(
            @NotBlank @Size(max = 300) String filename,
            @Positive long size,
            @Size(max = 200) String contentType,
            @NotNull UploadTarget target,
            /** QUICK_UPLOAD: the project folder to file the new ticket under. */
            Long projectId,
            /** QUICK_UPLOAD: optional department the caller picked in the modal. */
            Long departmentId,
            /** TICKET_DOCUMENT: the ticket the file attaches to. */
            Long ticketId,
            /** QUICK_UPLOAD: ticket title. TICKET_DOCUMENT: document display name. */
            @Size(max = 250) String title
    ) {}

    /** Session state. {@code received} lists chunk indices already safely on disk, so a
     *  client that lost its connection can resume by sending only what's missing. */
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

    /** Exactly one of {@code ticket} / {@code document} is set, matching {@code target}. */
    public record CompleteResponse(
            UploadTarget target,
            TicketDtos.TicketResponse ticket,
            TicketDtos.DocumentResponse document
    ) {}
}
