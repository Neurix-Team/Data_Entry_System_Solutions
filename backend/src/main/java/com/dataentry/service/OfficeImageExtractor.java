package com.dataentry.service;

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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts embedded raster images out of ZIP-based office documents:
 * .docx / .docm, .xlsx / .xlsm, .pptx / .pptm, and OpenDocument (.odt / .ods / .odp).
 * <p>
 * All of these formats are ZIP containers with images living under a well-known
 * subdirectory ({@code word/media/}, {@code xl/media/}, {@code ppt/media/},
 * {@code Pictures/}). Copies each image bytes-as-is (no re-encoding, so JPEGs
 * keep their compression and PNGs stay lossless) and reads dimensions back off
 * the saved file via ImageIO.
 * <p>
 * Skips tiny decorations (below {@link #minSide}px on either axis) and caps
 * the per-doc count at {@link #maxImages}, mirroring {@link PdfImageExtractor}.
 * <p>
 * Legacy binary formats (.doc / .xls / .ppt) are NOT supported — they need the
 * POI HWPF/HSSF/HSLF APIs, which is out of scope for the current build.
 */
@Service
public class OfficeImageExtractor {

    private static final Logger log = LoggerFactory.getLogger(OfficeImageExtractor.class);

    /** ZIP path prefixes where the different office formats keep their media. */
    private static final Set<String> MEDIA_PREFIXES = Set.of(
            "word/media/",
            "xl/media/",
            "ppt/media/",
            "Pictures/"
    );

    /** Recognised image extensions inside those media folders. .emf / .wmf are
     *  Windows metafile formats we intentionally skip — the browser can't render
     *  them and re-rasterising would need extra dependencies. */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "tif", "tiff"
    );

    private final int minSide;
    private final int maxImages;

    public OfficeImageExtractor(
            @Value("${app.pdf.images.min-side-px:64}") int minSide,
            @Value("${app.pdf.images.max-per-doc:40}") int maxImages) {
        this.minSide = minSide;
        this.maxImages = maxImages;
    }

    public record Extracted(
            String filename,
            String contentType,
            int width,
            int height,
            long sizeBytes
    ) {}

    /** True when {@code originalFilename} points at a format this extractor handles. */
    public static boolean supports(String originalFilename) {
        if (originalFilename == null) return false;
        String lower = originalFilename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".docx") || lower.endsWith(".docm")
                || lower.endsWith(".xlsx") || lower.endsWith(".xlsm")
                || lower.endsWith(".pptx") || lower.endsWith(".pptm")
                || lower.endsWith(".odt")  || lower.endsWith(".ods")
                || lower.endsWith(".odp");
    }

    /**
     * Walk the office file's ZIP entries and drop qualifying images into {@code outputDir}.
     * Files are named {@code image-01.png} onwards (extension preserved from the source).
     */
    public List<Extracted> extractInto(File officeFile, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        List<Extracted> out = new ArrayList<>();

        try (ZipFile zip = new ZipFile(officeFile)) {
            var entries = zip.entries();
            int index = 0;
            while (entries.hasMoreElements()) {
                if (out.size() >= maxImages) {
                    log.info("Reached image cap ({}) — stopping extraction", maxImages);
                    break;
                }
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String name = entry.getName();
                if (!hasMediaPrefix(name)) continue;

                String ext = extensionOf(name);
                if (ext.isEmpty() || !IMAGE_EXTENSIONS.contains(ext)) continue;

                index++;
                String outName = String.format("image-%02d.%s", index, normalisedExt(ext));
                Path target = outputDir.resolve(outName);

                // Copy bytes as-is — no re-encoding, so JPEGs stay compressed and
                // PNGs stay lossless.
                try (var in = zip.getInputStream(entry)) {
                    Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    log.debug("Skipping unreadable zip entry {}: {}", name, e.getMessage());
                    index--;
                    continue;
                }

                // Read dimensions off the saved file. If it fails (corrupt / unsupported),
                // drop the file — we don't want to hand the client something broken.
                Dimensions dims = readDimensions(target);
                if (dims == null || dims.width < minSide || dims.height < minSide) {
                    Files.deleteIfExists(target);
                    index--;
                    continue;
                }

                long size = Files.size(target);
                out.add(new Extracted(outName, contentTypeFor(ext), dims.width, dims.height, size));
            }
        }

        log.info("Extracted {} image(s) from '{}' (min-side={}px, cap={})",
                out.size(), officeFile.getName(), minSide, maxImages);
        return out;
    }

    private boolean hasMediaPrefix(String zipEntryName) {
        for (String prefix : MEDIA_PREFIXES) {
            if (zipEntryName.startsWith(prefix)) return true;
        }
        return false;
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Normalise the extension we emit — jpeg → jpg so the URL / filename stays predictable. */
    private String normalisedExt(String ext) {
        if ("jpeg".equals(ext)) return "jpg";
        if ("tiff".equals(ext)) return "tif";
        return ext;
    }

    private String contentTypeFor(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "tif", "tiff" -> "image/tiff";
            default -> "application/octet-stream";
        };
    }

    private record Dimensions(int width, int height) {}

    private Dimensions readDimensions(Path file) {
        try {
            BufferedImage img = ImageIO.read(file.toFile());
            if (img == null) return null;
            Dimensions d = new Dimensions(img.getWidth(), img.getHeight());
            img.flush();
            return d;
        } catch (IOException e) {
            return null;
        }
    }
}
