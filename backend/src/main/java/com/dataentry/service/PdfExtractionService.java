package com.dataentry.service;

import com.dataentry.dto.PdfDtos;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Bridges Spring uploads to opendataloader-pdf. Because the library writes its output to a folder
 * rather than returning it, we run each request against an isolated temp folder, read back the
 * generated markdown/text, then delete the folder.
 */
@Service
public class PdfExtractionService {

    private static final Logger log = LoggerFactory.getLogger(PdfExtractionService.class);

    private final Path baseOutputDir;
    private final int maxChars;
    private final PdfOcrService ocrService;

    public PdfExtractionService(
            @Value("${app.pdf.output-dir}") String outputDir,
            @Value("${app.pdf.max-chars:200000}") int maxChars,
            PdfOcrService ocrService) {
        this.baseOutputDir = Paths.get(outputDir);
        this.maxChars = maxChars;
        this.ocrService = ocrService;
        try {
            Files.createDirectories(this.baseOutputDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create PDF output directory: " + baseOutputDir, e);
        }
    }

    public PdfDtos.ExtractedContentResponse extract(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF file is required");
        }
        String originalName = file.getOriginalFilename() == null ? "upload.pdf" : file.getOriginalFilename();
        log.info("PDF extraction request: name='{}' size={} bytes contentType={}",
                originalName, file.getSize(), file.getContentType());

        if (!originalName.toLowerCase(Locale.ROOT).endsWith(".pdf")
                && !"application/pdf".equalsIgnoreCase(file.getContentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF files are supported");
        }

        Path work = null;
        Path input = null;
        List<String> warnings = new ArrayList<>();
        long started = System.currentTimeMillis();
        try {
            work = Files.createTempDirectory(baseOutputDir, "job-");
            input = work.resolve(sanitizeFileName(originalName));
            file.transferTo(input.toFile());
            log.debug("Saved upload to {} ({} bytes)", input, Files.size(input));

            invokeLibrary(input, work, warnings);

            String markdown = readFirstMatching(work, ".md");
            if (markdown == null) {
                markdown = readFirstMatching(work, ".txt");
            }

            String plain;
            boolean usedOcr = false;

            if (markdown != null && !markdown.isBlank() && !PdfOcrService.containsRtl(markdown)) {
                // Text extraction worked and the text is not RTL — use it as-is.
                plain = stripMarkdown(markdown);
            } else {
                // Either extraction failed OR the text is RTL and likely reversed by
                // opendataloader-pdf. Fall back to rendering pages as images + OCR.
                String reason = (markdown == null || markdown.isBlank())
                        ? "opendataloader-pdf produced no text"
                        : "opendataloader-pdf produced RTL text that may be reversed";
                log.info("Falling back to OCR pipeline for '{}' — {}", originalName, reason);
                warnings.add("Switched to OCR extraction to preserve Arabic reading order");

                String ocrText;
                try {
                    ocrText = ocrService.ocrPdf(input.toFile());
                } catch (IOException e) {
                    log.error("OCR fallback failed for '{}'", originalName, e);
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Could not read the PDF via OCR: " + e.getMessage());
                } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
                    log.error("Tesseract native library missing", e);
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "OCR engine is not available on this server");
                }

                if (ocrText == null || ocrText.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Could not extract any text from this PDF. It may be encrypted or empty.");
                }
                plain = ocrText;
                markdown = ocrText;
                usedOcr = true;
            }

            boolean truncated = false;
            if (plain.length() > maxChars) {
                plain = plain.substring(0, maxChars);
                truncated = true;
                warnings.add("Text was truncated to " + maxChars + " characters");
            }

            log.info("PDF extraction OK: name='{}' chars={} took={}ms via={}",
                    originalName, plain.length(),
                    (System.currentTimeMillis() - started),
                    usedOcr ? "OCR" : "opendataloader");

            return new PdfDtos.ExtractedContentResponse(
                    originalName,
                    plain,
                    markdown,
                    plain.length(),
                    truncated,
                    Instant.now(),
                    warnings
            );
        } catch (IOException e) {
            log.error("Failed to persist upload '{}'", originalName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read the uploaded file");
        } catch (OutOfMemoryError e) {
            log.error("Out of memory while extracting '{}'", originalName, e);
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "The PDF is too large or too complex for the server to process. Try splitting it into smaller files.");
        } finally {
            if (work != null) deleteRecursively(work);
        }
    }

    private String listContents(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .toList()
                    .toString();
        } catch (IOException e) {
            return "(unreadable)";
        }
    }

    /**
     * Reflection-based call so a version bump on opendataloader-pdf that renames a Config method
     * only fails the request (with a helpful log) instead of the whole app.
     */
    private void invokeLibrary(Path input, Path outputDir, List<String> warnings) {
        try {
            Class<?> configCls = Class.forName("org.opendataloader.pdf.api.Config");
            Class<?> loaderCls = Class.forName("org.opendataloader.pdf.api.OpenDataLoaderPDF");
            Object cfg = configCls.getDeclaredConstructor().newInstance();

            trySet(cfg, "setOutputFolder", String.class, outputDir.toAbsolutePath().toString());
            trySet(cfg, "setGenerateMarkdown", boolean.class, true);
            trySet(cfg, "setGenerateJSON", boolean.class, false);
            trySet(cfg, "setGenerateHTML", boolean.class, false);
            trySet(cfg, "setGeneratePDF", boolean.class, false);
            trySet(cfg, "setGenerateText", boolean.class, true);

            Method processFile;
            try {
                processFile = loaderCls.getMethod("processFile", String.class, configCls);
                processFile.invoke(null, input.toAbsolutePath().toString(), cfg);
            } catch (NoSuchMethodException e) {
                processFile = loaderCls.getMethod("processFile", Path.class, configCls);
                processFile.invoke(null, input.toAbsolutePath(), cfg);
            }
        } catch (ClassNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PDF extraction library is not available on the classpath");
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("PDF extraction failed for input {}", input, cause);
            String reason = cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PDF extraction failed — " + reason);
        }
    }

    private void trySet(Object target, String method, Class<?> paramType, Object value) {
        try {
            Method m = target.getClass().getMethod(method, paramType);
            m.invoke(target, value);
        } catch (NoSuchMethodException ignored) {
            // this Config version does not expose the setter; ignore
        } catch (ReflectiveOperationException e) {
            log.debug("Config setter {} failed: {}", method, e.getMessage());
        }
    }

    private String readFirstMatching(Path dir, String suffix) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            Optional<Path> match = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                    .findFirst();
            if (match.isEmpty()) return null;
            return Files.readString(match.get());
        }
    }

    private String stripMarkdown(String md) {
        // Light-touch cleanup so the content field reads naturally.
        return md
                .replaceAll("(?m)^#{1,6}\\s+", "")
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")
                .replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1")
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("__(.+?)__", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("(?m)^\\s*[-*+]\\s+", "- ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String sanitizeFileName(String name) {
        String base = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return base.length() > 120 ? base.substring(0, 120) : base;
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to clean up temp directory {}: {}", dir, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        try {
            Class<?> loaderCls = Class.forName("org.opendataloader.pdf.api.OpenDataLoaderPDF");
            Method shutdown = loaderCls.getMethod("shutdown");
            shutdown.invoke(null);
        } catch (Throwable t) {
            log.debug("PDF library shutdown skipped: {}", t.getMessage());
        }
    }
}
