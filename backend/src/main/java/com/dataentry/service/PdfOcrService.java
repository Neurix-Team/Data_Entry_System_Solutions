package com.dataentry.service;

import net.sourceforge.tess4j.Tesseract;
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

/**
 * OCR-based PDF extraction. Renders each page as an image via PDFBox and runs Tesseract on it.
 * Used as a fallback for PDFs where the native text stream produces garbled RTL (Arabic) text,
 * or for scanned PDFs where opendataloader-pdf cannot read the text.
 */
@Service
public class PdfOcrService {

    private static final Logger log = LoggerFactory.getLogger(PdfOcrService.class);

    private final String tessdataPath;
    private final String languages;
    private final int renderDpi;
    private final int maxPages;

    public PdfOcrService(
            @Value("${app.ocr.tessdata-path:/usr/share/tesseract-ocr/4.00/tessdata/}") String tessdataPath,
            @Value("${app.ocr.languages:ara+eng}") String languages,
            @Value("${app.ocr.pdf-dpi:200}") int renderDpi,
            @Value("${app.ocr.pdf-max-pages:200}") int maxPages) {
        this.tessdataPath = tessdataPath;
        this.languages = languages;
        this.renderDpi = renderDpi;
        this.maxPages = maxPages;
    }

    /** OCR each page of the PDF and return the concatenated text. */
    public String ocrPdf(File pdfFile) throws IOException {
        long started = System.currentTimeMillis();
        StringBuilder out = new StringBuilder();

        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            int total = doc.getNumberOfPages();
            int pagesToProcess = Math.min(total, maxPages);
            log.info("PDF OCR: {} pages (processing {}), dpi={}, langs={}",
                    total, pagesToProcess, renderDpi, languages);

            PDFRenderer renderer = new PDFRenderer(doc);
            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath(tessdataPath);
            tesseract.setLanguage(languages);
            // PSM 3 = fully automatic page segmentation (default)
            // OEM 1 = LSTM only (best accuracy, ships with tesseract 4.x)
            tesseract.setPageSegMode(3);
            tesseract.setOcrEngineMode(1);

            for (int page = 0; page < pagesToProcess; page++) {
                try {
                    BufferedImage image = renderer.renderImageWithDPI(page, renderDpi, ImageType.RGB);
                    String pageText = tesseract.doOCR(image);
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

    /**
     * Removes OCR noise: lines that are mostly garbage characters produced by Tesseract
     * misreading logos, decorative elements, illustrations, or table borders as text.
     * Keeps lines that look like real Arabic or Latin text.
     */
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

            // Strip stray leading/trailing symbols that aren't part of real punctuation
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

    /**
     * A line is considered noise if fewer than ~55% of its non-space characters look like
     * meaningful text (letters, digits, common punctuation), OR if it is a single-char
     * fragment, OR if it is dominated by table-drawing / decorative symbols.
     */
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

        // Short lines dominated by numbers/symbols (page footers with random OCR noise)
        double meaningfulRatio = (double) meaningful / totalChars;
        if (meaningfulRatio < 0.55) return true;

        // Very high symbol density = decorative element or table border
        if (totalChars >= 4 && (double) symbols / totalChars > 0.35) return true;

        // Table-drawing pipes / underscores dominating the line
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

    /**
     * Detects if a string contains characters that look like they came from a right-to-left
     * script (Arabic / Hebrew), i.e. cases where opendataloader-pdf's text extraction is
     * likely to produce reversed / garbled output.
     */
    public static boolean containsRtl(String text) {
        if (text == null) return false;
        int rtlCount = 0;
        int total = Math.min(text.length(), 2000);
        for (int i = 0; i < total; i++) {
            char c = text.charAt(i);
            // Arabic block: U+0600–U+06FF, Arabic Supplement U+0750–U+077F,
            // Arabic Presentation Forms-A U+FB50–U+FDFF, Forms-B U+FE70–U+FEFF, Hebrew U+0590–U+05FF
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
