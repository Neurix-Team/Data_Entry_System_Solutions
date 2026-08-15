package com.dataentry.controller;

import com.dataentry.dto.PdfDtos;
import com.dataentry.model.User;
import com.dataentry.service.DocumentExtractionService;
import com.dataentry.service.PdfExtractionService;
import com.dataentry.service.UploadQuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/user")
public class PdfController {

    private static final Logger log = LoggerFactory.getLogger(PdfController.class);

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
        return runSafely(() -> pdfService.extract(file), safeName(file));
    }

    /** Unified endpoint: PDF, Word, Excel, PowerPoint, images, plain text. */
    @PostMapping(value = "/documents/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PdfDtos.ExtractedContentResponse extractDocument(@RequestPart("file") MultipartFile file,
                                                            @AuthenticationPrincipal User user) {
        quota.chargeOrThrow(user.getId(), file.getSize());
        return runSafely(() -> documentService.extract(file), safeName(file));
    }

    /**
     * Wraps an extraction call so any unexpected exception surfaces to the client as a
     * targeted 422 with the file name and exception type, instead of the generic 500
     * "Unexpected server error" from {@code GlobalExceptionHandler}. The full stack trace
     * is still logged server-side for diagnosis.
     */
    private PdfDtos.ExtractedContentResponse runSafely(
            java.util.function.Supplier<PdfDtos.ExtractedContentResponse> op, String fileLabel) {
        try {
            return op.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Extraction failed unexpectedly for '{}'", fileLabel, e);
            String cause = e.getClass().getSimpleName();
            String msg = e.getMessage();
            String detail = msg == null || msg.isBlank() ? cause : cause + ": " + msg;
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not extract '" + fileLabel + "' — " + detail);
        }
    }

    private String safeName(MultipartFile file) {
        String n = file == null ? null : file.getOriginalFilename();
        return n == null || n.isBlank() ? "upload" : n;
    }
}
