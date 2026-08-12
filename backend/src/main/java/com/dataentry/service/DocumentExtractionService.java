package com.dataentry.service;

import com.dataentry.dto.PdfDtos;
import net.sourceforge.tess4j.Tesseract;
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
    private final String tessdataPath;
    private final String ocrLanguages;

    public DocumentExtractionService(
            PdfExtractionService pdfService,
            @Value("${app.pdf.max-chars:200000}") int maxChars,
            @Value("${app.ocr.tessdata-path:/usr/share/tesseract-ocr/4.00/tessdata/}") String tessdataPath,
            @Value("${app.ocr.languages:ara+eng}") String ocrLanguages) {
        this.pdfService = pdfService;
        this.maxChars = maxChars;
        this.tessdataPath = tessdataPath;
        this.ocrLanguages = ocrLanguages;
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
        try (InputStream in = file.getInputStream()) {
            BodyContentHandler handler = new BodyContentHandler(Math.max(maxChars * 4, 1_000_000));
            Metadata metadata = new Metadata();
            metadata.set(Metadata.CONTENT_TYPE, file.getContentType() == null ? MimeTypes.OCTET_STREAM : file.getContentType());
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, originalName);
            // Harden the parse context against XXE / entity-expansion attacks in Office / RTF /
            // XML documents.  Tika's default ParseContext trusts DOCTYPEs and external entities.
            new AutoDetectParser().parse(in, handler, metadata, hardenedParseContext());
            String text = handler.toString();

            if (text == null || text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Could not extract any text from the file");
            }
            return finalize(originalName, text, warnings);
        } catch (SAXException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("Your document contained more than")) {
                warnings.add("Document is very large — extraction stopped at the internal Tika limit");
                try (InputStream in = file.getInputStream()) {
                    String snippet = tika.parseToString(in);
                    return finalize(originalName, snippet, warnings);
                } catch (IOException | TikaException fallback) {
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

    private PdfDtos.ExtractedContentResponse extractWithOcr(MultipartFile file, String originalName) {
        List<String> warnings = new ArrayList<>();
        Path tmp = null;
        try {
            tmp = Files.createTempFile("ocr-", "-" + safeName(originalName));
            file.transferTo(tmp.toFile());

            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath(tessdataPath);
            tesseract.setLanguage(ocrLanguages);
            String text = tesseract.doOCR(tmp.toFile());
            if (text == null || text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "OCR produced no text from this image");
            }
            return finalize(originalName, text, warnings);
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

    private PdfDtos.ExtractedContentResponse finalize(String filename, String rawText, List<String> warnings) {
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
                null,     // non-PDF paths don't emit staged images
                List.of()
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
