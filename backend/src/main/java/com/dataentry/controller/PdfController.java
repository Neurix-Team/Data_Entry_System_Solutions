package com.dataentry.controller;

import com.dataentry.dto.PdfDtos;
import com.dataentry.model.User;
import com.dataentry.service.DocumentExtractionService;
import com.dataentry.service.PdfExtractionService;
import com.dataentry.service.UploadQuotaService;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/user")
public class PdfController {

    private final PdfExtractionService pdfService;
    private final DocumentExtractionService documentService;
    private final UploadQuotaService quota;

    public PdfController(PdfExtractionService pdfService,
                         DocumentExtractionService documentService,
                         UploadQuotaService quota) {
        this.pdfService = pdfService;
        this.documentService = documentService;
        this.quota = quota;
    }

    /** Legacy PDF-only endpoint — kept for callers pinned to /pdf/extract. */
    @PostMapping(value = "/pdf/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PdfDtos.ExtractedContentResponse extractPdf(@RequestPart("file") MultipartFile file,
                                                       @AuthenticationPrincipal User user) {
        quota.chargeOrThrow(user.getId(), file.getSize());
        return pdfService.extract(file);
    }

    /** Unified endpoint: PDF, Word, Excel, PowerPoint, images, plain text. */
    @PostMapping(value = "/documents/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PdfDtos.ExtractedContentResponse extractDocument(@RequestPart("file") MultipartFile file,
                                                            @AuthenticationPrincipal User user) {
        quota.chargeOrThrow(user.getId(), file.getSize());
        return documentService.extract(file);
    }
}
