package com.dataentry.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks every page of a PDF via PDFBox and writes each embedded raster image to disk
 * as PNG. Runs after text extraction so both the markdown and OCR fallback paths get
 * the same asset set — the images live inside the PDF regardless of how text was read.
 * <p>
 * Skips images smaller than {@link #minSide}px on either axis (icons, bullet decorations,
 * form-field checkboxes) so the user isn't drowned in noise. De-duplicates by the PDF's
 * own object name so a repeated logo across N pages is written once.
 */
@Service
public class PdfImageExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfImageExtractor.class);

    private final int minSide;
    private final int maxImages;

    public PdfImageExtractor(
            @Value("${app.pdf.images.min-side-px:64}") int minSide,
            @Value("${app.pdf.images.max-per-doc:40}") int maxImages) {
        this.minSide = minSide;
        this.maxImages = maxImages;
    }

    public record Extracted(String filename, int page, int width, int height, long sizeBytes) {}

    /**
     * Extract every raster image into {@code outputDir}. Files are named {@code image-01.png}
     * upwards in page-then-appearance order. Vector-only pages contribute nothing.
     * Fails soft: an image that can't be read is logged and skipped, not thrown.
     */
    public List<Extracted> extractInto(File pdfFile, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        List<Extracted> out = new ArrayList<>();
        Set<String> seenObjectKeys = new HashSet<>();

        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            int pageNumber = 0;
            int index = 0;
            for (PDPage page : doc.getPages()) {
                pageNumber++;
                PDResources resources = page.getResources();
                if (resources == null) continue;

                for (COSName name : resources.getXObjectNames()) {
                    if (out.size() >= maxImages) {
                        log.info("Reached image cap ({}) — stopping extraction", maxImages);
                        return out;
                    }
                    PDXObject xobj;
                    try {
                        xobj = resources.getXObject(name);
                    } catch (IOException e) {
                        log.debug("Skipping unreadable XObject {}: {}", name.getName(), e.getMessage());
                        continue;
                    }
                    if (!(xobj instanceof PDImageXObject img)) continue;

                    // De-dupe: the same COSName across pages is the same underlying object.
                    // COSName alone can collide across pages so we key on identity via toString().
                    String key = name.getName() + "@" + System.identityHashCode(img.getCOSObject());
                    if (!seenObjectKeys.add(key)) continue;

                    int w = img.getWidth();
                    int h = img.getHeight();
                    if (w < minSide || h < minSide) continue;

                    BufferedImage bi;
                    try {
                        bi = img.getImage();
                    } catch (IOException | RuntimeException e) {
                        // Some PDFs contain images with unsupported filters (JBIG2 without the
                        // optional plugin, JPEG2000, exotic ICC profiles). Log and move on.
                        log.debug("Skipping image on page {} — decode failed: {}", pageNumber, e.getMessage());
                        continue;
                    }
                    if (bi == null) continue;

                    index++;
                    String filename = String.format("image-%02d.png", index);
                    Path target = outputDir.resolve(filename);
                    try {
                        ImageIO.write(bi, "png", target.toFile());
                    } catch (IOException e) {
                        log.warn("Failed to write extracted image {}: {}", filename, e.getMessage());
                        continue;
                    } finally {
                        bi.flush();
                    }

                    long size = Files.size(target);
                    out.add(new Extracted(filename, pageNumber, w, h, size));
                }
            }
        }

        log.info("Extracted {} image(s) from '{}' (min-side={}px, cap={})",
                out.size(), pdfFile.getName(), minSide, maxImages);
        return out;
    }
}
