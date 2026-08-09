package com.dataentry.controller;

import com.dataentry.dto.PdfDtos;
import com.dataentry.service.DocumentExtractionService;
import com.dataentry.service.PdfExtractionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/user")
public class PdfController {

    private final PdfExtractionService pdfService;
    private final DocumentExtractionService documentService;

    public PdfController(PdfExtractionService pdfService, DocumentExtractionService documentService) {
        this.pdfService = pdfService;
        this.documentService = documentService;
    }

    /** Legacy PDF-only endpoint — kept for callers pinned to /pdf/extract. */
    @PostMapping(value = "/pdf/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PdfDtos.ExtractedContentResponse extractPdf(@RequestPart("file") MultipartFile file) {
        return pdfService.extract(file);
    }

    /** Unified endpoint: PDF, Word, Excel, PowerPoint, images, plain text. */
    @PostMapping(value = "/documents/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PdfDtos.ExtractedContentResponse extractDocument(@RequestPart("file") MultipartFile file) {
        return documentService.extract(file);
    }
}
