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
