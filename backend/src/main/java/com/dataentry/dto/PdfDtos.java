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
            List<String> warnings
    ) {}
}
