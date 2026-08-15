package com.dataentry.service;

import com.dataentry.dto.PdfDtos;
import com.dataentry.model.User;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MimeTypes;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Single entry-point for extracting text from any uploaded file. Routes by MIME:
 * PDF -> {@link PdfExtractionService} (has OCR for scanned pages),
 * images (jpg/png/tiff/...) -> Tesseract (via Tess4J) with Arabic + English,
 * everything else Tika supports (Word / Excel / PowerPoint / ODF / RTF / EPUB / CSV / HTML / TXT)
 * -> Apache Tika.
 */
@Service
public class DocumentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractionService.class);

    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/tiff",
            "image/bmp", "image/gif", "image/webp"
    );

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "pdf",
            "doc", "docx", "docm",
            "xls", "xlsx", "xlsm", "xlsb", "csv",
            "ppt", "pptx", "pptm",
            "odt", "ods", "odp",
            "rtf", "epub",
            "txt", "md", "html", "htm", "xml", "json",
            "jpg", "jpeg", "png", "tiff", "tif", "bmp", "gif", "webp"
    );

    private final PdfExtractionService pdfService;
    private final Tika tika = new Tika();
    private final int maxChars;
    private final OfficeImageExtractor officeImageExtractor;
    private final ExtractionStagingService staging;
    private final OcrGate ocrGate;

    public DocumentExtractionService(
            PdfExtractionService pdfService,
            @Value("${app.pdf.max-chars:2000000}") int maxChars,
            OfficeImageExtractor officeImageExtractor,
            ExtractionStagingService staging,
            OcrGate ocrGate) {
        this.pdfService = pdfService;
        this.maxChars = maxChars;
        this.officeImageExtractor = officeImageExtractor;
        this.staging = staging;
        this.ocrGate = ocrGate;
    }

    public PdfDtos.ExtractedContentResponse extract(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is required");
        }

        String originalName = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String extension = extensionOf(originalName);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported file type '." + extension + "'. Allowed: PDF, Word, Excel, PowerPoint, images, and plain text.");
        }

        String contentType = file.getContentType();
        if ("pdf".equals(extension) || "application/pdf".equalsIgnoreCase(contentType)) {
            return pdfService.extract(file);
        }

        if (isImage(extension, contentType)) {
            return extractWithOcr(file, originalName);
        }

        return extractWithTika(file, originalName);
    }

    private PdfDtos.ExtractedContentResponse extractWithTika(MultipartFile file, String originalName) {
        List<String> warnings = new ArrayList<>();
        // Cross-check the declared MIME against what Tika actually detects in the bytes — blocks
        // .exe-renamed-to-.pdf spoofing.  Detection reads the first few KB from a buffered copy.
        String detected;
        try (InputStream detectIn = file.getInputStream()) {
            detected = tika.detect(detectIn, originalName);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not read uploaded file: " + e.getMessage());
        }
        if (detected != null && (detected.startsWith("application/x-msdownload")
                || detected.startsWith("application/x-executable")
                || detected.startsWith("application/x-sharedlib")
                || detected.startsWith("application/x-mach-binary"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Executable files are not accepted.");
        }

        // Persist the upload to a temp file so both Tika text extraction and the
        // office image extractor can read it — the latter needs a random-access File,
        // not a stream.
        Path tmp = null;
        try {
            tmp = Files.createTempFile("doc-", "-" + safeName(originalName));
            file.transferTo(tmp.toFile());

            String text = parseTikaText(tmp, file.getContentType(), originalName, warnings);
            if (text == null || text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Could not extract any text from the file");
            }
            StagedImages staged = extractOfficeImages(tmp.toFile(), originalName, warnings);
            return finalize(originalName, text, warnings, staged);
        } catch (IOException e) {
            log.error("Failed to persist office upload '{}'", originalName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not read the uploaded file");
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    /** Runs Tika on the persisted file. Falls back to Tika's parseToString when the
     *  document exceeds SAX's default limit, matching the previous behaviour. */
    private String parseTikaText(Path file, String declaredContentType, String originalName,
                                 List<String> warnings) {
        try (InputStream in = Files.newInputStream(file)) {
            BodyContentHandler handler = new BodyContentHandler(Math.max(maxChars * 4, 1_000_000));
            Metadata metadata = new Metadata();
            metadata.set(Metadata.CONTENT_TYPE, declaredContentType == null ? MimeTypes.OCTET_STREAM : declaredContentType);
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, originalName);
            new AutoDetectParser().parse(in, handler, metadata, hardenedParseContext());
            return handler.toString();
        } catch (SAXException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("Your document contained more than")) {
                warnings.add("Document is very large — extraction stopped at the internal Tika limit");
                // Retry with a much larger body handler, but keep the hardened parse context so
                // external entities and DTDs are still refused. Plain `tika.parseToString` would
                // reopen the XXE hole exactly on the large-document path most exposed to attack.
                try (InputStream in = Files.newInputStream(file)) {
                    BodyContentHandler bigHandler = new BodyContentHandler(-1);
                    Metadata metadata = new Metadata();
                    metadata.set(Metadata.CONTENT_TYPE,
                            declaredContentType == null ? MimeTypes.OCTET_STREAM : declaredContentType);
                    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, originalName);
                    new AutoDetectParser().parse(in, bigHandler, metadata, hardenedParseContext());
                    return bigHandler.toString();
                } catch (IOException | TikaException | SAXException fallback) {
                    log.warn("Tika fallback failed", fallback);
                }
            }
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Extraction failed: " + msg);
        } catch (TikaException | IOException e) {
            log.error("Tika parse failed", e);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Extraction failed: " + e.getMessage());
        }
    }

    /** Pull embedded images out of an office document into the staging area, or return
     *  empty for formats we don't handle. Non-fatal — text is the primary deliverable. */
    private StagedImages extractOfficeImages(File file, String originalName, List<String> warnings) {
        if (!OfficeImageExtractor.supports(originalName)) return StagedImages.empty();
        Long ownerId = currentUserId();
        if (ownerId == null) return StagedImages.empty();

        try {
            ExtractionStagingService.Handle handle = staging.create(ownerId);
            List<OfficeImageExtractor.Extracted> found = officeImageExtractor.extractInto(file, handle.directory());
            if (found.isEmpty()) {
                staging.discard(handle.extractionId());
                return StagedImages.empty();
            }
            List<PdfDtos.ExtractedImage> images = found.stream()
                    .map(f -> new PdfDtos.ExtractedImage(
                            f.filename(),
                            "/api/user/extractions/" + handle.extractionId() + "/images/" + f.filename(),
                            f.contentType(),
                            f.sizeBytes(),
                            0,   // page number doesn't apply to office documents
                            f.width(),
                            f.height()))
                    .toList();
            return new StagedImages(handle.extractionId(), images);
        } catch (Exception e) {
            log.warn("Office image extraction failed for '{}' — continuing with text only: {}",
                    originalName, e.getMessage());
            warnings.add("Could not extract images from this document");
            return StagedImages.empty();
        }
    }

    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        return auth.getPrincipal() instanceof User u ? u.getId() : null;
    }

    private record StagedImages(String extractionId, List<PdfDtos.ExtractedImage> images) {
        static StagedImages empty() { return new StagedImages(null, List.of()); }
    }

    private PdfDtos.ExtractedContentResponse extractWithOcr(MultipartFile file, String originalName) {
        List<String> warnings = new ArrayList<>();
        Path tmp = null;
        try {
            tmp = Files.createTempFile("ocr-", "-" + safeName(originalName));
            file.transferTo(tmp.toFile());

            String text = ocrGate.ocrFile(tmp.toFile());
            if (text == null || text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "OCR produced no text from this image");
            }
            return finalize(originalName, text, warnings, StagedImages.empty());
        } catch (net.sourceforge.tess4j.TesseractException e) {
            log.error("Tesseract OCR failed", e);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "OCR failed: " + e.getMessage());
        } catch (IOException e) {
            log.error("Failed to persist image upload", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not read the uploaded image");
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            log.error("Tesseract native library is missing", e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OCR engine is not available on this server");
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private PdfDtos.ExtractedContentResponse finalize(String filename, String rawText,
                                                      List<String> warnings, StagedImages staged) {
        String cleaned = rawText.replaceAll("\\n{3,}", "\n\n").trim();
        boolean truncated = false;
        if (cleaned.length() > maxChars) {
            cleaned = cleaned.substring(0, maxChars);
            truncated = true;
            warnings.add("Text was truncated to " + maxChars + " characters");
        }
        return new PdfDtos.ExtractedContentResponse(
                filename,
                cleaned,
                cleaned,
                cleaned.length(),
                truncated,
                Instant.now(),
                warnings,
                staged.extractionId(),
                staged.images()
        );
    }

    private boolean isImage(String extension, String contentType) {
        if (contentType != null && IMAGE_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return Set.of("jpg", "jpeg", "png", "tiff", "tif", "bmp", "gif", "webp").contains(extension);
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String safeName(String name) {
        String base = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return base.length() > 80 ? base.substring(0, 80) : base;
    }

    /**
     * A ParseContext with a SAX parser factory that refuses to resolve external entities and
     * DTDs.  Blocks XXE / XML-bomb payloads hidden in Office / OpenDocument / RTF files.
     */
    private ParseContext hardenedParseContext() {
        ParseContext ctx = new ParseContext();
        try {
            javax.xml.parsers.SAXParserFactory spf = javax.xml.parsers.SAXParserFactory.newInstance();
            spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            spf.setXIncludeAware(false);
            spf.setNamespaceAware(true);
            ctx.set(javax.xml.parsers.SAXParserFactory.class, spf);

            javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            ctx.set(javax.xml.parsers.DocumentBuilderFactory.class, dbf);
        } catch (Exception e) {
            log.warn("Could not fully harden XML parser factories: {}", e.getMessage());
        }
        return ctx;
    }
}
