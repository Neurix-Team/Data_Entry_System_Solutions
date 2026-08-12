package com.dataentry.dto;

import java.time.Instant;
import java.util.List;

public class PdfDtos {

    public record ExtractedContentResponse(
            String filename,
            String text,
            String markdown,
            int characters,
            boolean truncated,
            Instant extractedAt,
            List<String> warnings,
            String extractionId,
            List<ExtractedImage> images
    ) {}

    /**
     * A single image pulled out of the uploaded file and written to the staging area.
     * The {@code url} is a relative API path served by ExtractionController; the browser
     * can render it directly (with the auth cookie attached).
     * The {@code extractionId} + {@code filename} pair is what the client sends back on
     * ticket submit so the server can promote the file into the ticket's attachments.
     */
    public record ExtractedImage(
            String filename,
            String url,
            String contentType,
            long sizeBytes,
            int page,
            int width,
            int height
    ) {}
}
