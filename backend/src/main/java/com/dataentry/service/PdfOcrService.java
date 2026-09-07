package com.dataentry.service;

import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

@Service
public class PdfOcrService {

    private static final Logger log = LoggerFactory.getLogger(PdfOcrService.class);

    private final int renderDpi;
    private final int maxPages;
    private final OcrGate ocrGate;

    public PdfOcrService(
            @Value("${app.ocr.pdf-dpi:200}") int renderDpi,
            @Value("${app.ocr.pdf-max-pages:200}") int maxPages,
            OcrGate ocrGate) {
        this.renderDpi = renderDpi;
        this.maxPages = maxPages;
        this.ocrGate = ocrGate;
    }

    public String ocrPdf(File pdfFile) throws IOException {
        long started = System.currentTimeMillis();
        StringBuilder out = new StringBuilder();

        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            int total = doc.getNumberOfPages();
            int pagesToProcess = Math.min(total, maxPages);
            log.info("PDF OCR: {} pages (processing {}), dpi={}", total, pagesToProcess, renderDpi);

            PDFRenderer renderer = new PDFRenderer(doc);

            for (int page = 0; page < pagesToProcess; page++) {
                try {
                    BufferedImage image = renderer.renderImageWithDPI(page, renderDpi, ImageType.RGB);
                    String pageText = ocrGate.run(t -> {
                        t.setPageSegMode(3);
                        t.setOcrEngineMode(1);
                        try {
                            return t.doOCR(image);
                        } catch (TesseractException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    String cleaned = cleanOcrOutput(pageText);
                    if (!cleaned.isBlank()) {
                        if (out.length() > 0) out.append("\n\n");
                        out.append(cleaned);
                    }
                    image.flush();
                } catch (TesseractException e) {
                    log.warn("OCR failed on page {}: {}", page + 1, e.getMessage());
                }
            }

            if (total > maxPages) {
                out.append("\n\n[Note: OCR limited to first ")
                        .append(maxPages).append(" of ").append(total).append(" pages]");
            }
        }

        long took = System.currentTimeMillis() - started;
        log.info("PDF OCR done in {}ms — extracted {} chars", took, out.length());
        return out.toString();
    }

    static String cleanOcrOutput(String text) {
        if (text == null || text.isBlank()) return "";

        StringBuilder result = new StringBuilder();
        boolean lastWasBlank = false;

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.strip();

            if (line.isEmpty()) {
                if (!lastWasBlank && result.length() > 0) {
                    result.append('\n');
                    lastWasBlank = true;
                }
                continue;
            }

            if (isNoiseLine(line)) {
                continue;
            }

            line = line.replaceAll("^[|\\\\/_=~`^*<>{}\\[\\]]+\\s*", "")
                       .replaceAll("\\s*[|\\\\/_=~`^*<>{}\\[\\]]+$", "")
                       .strip();
            if (line.isEmpty() || isNoiseLine(line)) continue;

            result.append(line).append('\n');
            lastWasBlank = false;
        }

        return result.toString()
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private static boolean isNoiseLine(String line) {
        int totalChars = 0;
        int meaningful = 0;
        int symbols = 0;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c)) continue;
            totalChars++;

            if (Character.isLetter(c) || Character.isDigit(c)) {
                meaningful++;
            } else if (isCommonPunct(c)) {
                meaningful++;
            } else {
                symbols++;
            }
        }

        if (totalChars == 0) return true;
        if (totalChars <= 2 && meaningful < totalChars) return true;

        double meaningfulRatio = (double) meaningful / totalChars;
        if (meaningfulRatio < 0.55) return true;

        if (totalChars >= 4 && (double) symbols / totalChars > 0.35) return true;

        long pipeCount = line.chars().filter(ch -> ch == '|' || ch == '_' || ch == '=' || ch == '-').count();
        if (totalChars > 0 && (double) pipeCount / totalChars > 0.5) return true;

        return false;
    }

    private static boolean isCommonPunct(char c) {
        return c == '.' || c == ',' || c == '؟' || c == '؛' || c == '،'
                || c == '!' || c == ':' || c == ';' || c == '\'' || c == '"'
                || c == '(' || c == ')' || c == '«' || c == '»'
                || c == '-' || c == '—' || c == '–' || c == '/' || c == '%';
    }

    public static boolean containsRtl(String text) {
        if (text == null) return false;
        int rtlCount = 0;
        int total = Math.min(text.length(), 2000);
        for (int i = 0; i < total; i++) {
            char c = text.charAt(i);
            if ((c >= 0x0600 && c <= 0x06FF)
                    || (c >= 0x0750 && c <= 0x077F)
                    || (c >= 0xFB50 && c <= 0xFDFF)
                    || (c >= 0xFE70 && c <= 0xFEFF)
                    || (c >= 0x0590 && c <= 0x05FF)) {
                rtlCount++;
                if (rtlCount > 20) return true;
            }
        }
        return false;
    }
}
