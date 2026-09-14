package com.dataentry.service;

import net.sourceforge.tess4j.TessAPI;
import net.sourceforge.tess4j.Tesseract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Knows whether Tesseract can actually run on this machine and where its language files
 * live. Probed once at startup and logged loudly, and exposed on /actuator/health as the
 * "ocr" component, so a server that answers "OCR engine is not available" can be
 * diagnosed from the log instead of by guesswork.
 *
 * <p>The probe goes all the way: it loads the native library, finds the tessdata
 * directory, checks each language file, then OCRs a tiny rendered image. The last step is
 * what catches a Leptonica/Tesseract version mismatch (tess4j binds to specific native
 * symbols), which only surfaces when an image is actually processed.
 *
 * <p>The tessdata directory is auto-detected: the configured path wins, then
 * {@code $TESSDATA_PREFIX}, then the places Debian/Ubuntu (Tesseract 5 and 4), Alpine,
 * Fedora, Homebrew and manual builds use. That is what makes the same jar work on a
 * Tesseract 5 host, where the directory is {@code .../5/tessdata} rather than
 * {@code .../4.00/tessdata}.
 */
@Component
public class OcrRuntime {

    private static final Logger log = LoggerFactory.getLogger(OcrRuntime.class);

    static final List<String> CANDIDATE_DIRS = List.of(
            "/usr/share/tesseract-ocr/5/tessdata",
            "/usr/share/tesseract-ocr/4.00/tessdata",
            "/usr/share/tessdata",
            "/usr/local/share/tessdata",
            "/usr/share/tesseract/tessdata",
            "/opt/homebrew/share/tessdata");

    static final String INSTALL_HINT =
            "Inside Docker: rebuild the image from the current Dockerfile (docker compose build backend "
            + "&& docker compose up -d backend). Bare metal: the app needs Tesseract 5 with Leptonica 1.85 "
            + "or newer (Ubuntu 26.04 packages: apt install tesseract-ocr tesseract-ocr-eng tesseract-ocr-ara "
            + "libtesseract-dev; Ubuntu 22.04/24.04 ship 1.82 and Debian 13 ships 1.84, both too old), then set "
            + "APP_OCR_TESSDATA_PATH if tessdata is somewhere unusual. See RUNBOOK.md, "
            + "\"OCR engine is not available\".";

    /** Text rendered into the smoke-test image; digits and capitals OCR reliably at any size. */
    static final String SMOKE_TEXT = "OCR 123";

    public record Status(boolean engineLoaded,
                         String engineVersion,
                         String datapath,
                         List<String> languages,
                         List<String> missingLanguages,
                         String smokeText,
                         String error) {
        public boolean ready() {
            return engineLoaded && datapath != null && missingLanguages.isEmpty() && error == null;
        }
    }

    private final String configuredPath;
    private final String languages;
    private final boolean selfTestEnabled;
    private volatile Status status;

    public OcrRuntime(@Value("${app.ocr.tessdata-path:}") String configuredPath,
                      @Value("${app.ocr.languages:ara+eng}") String languages,
                      @Value("${app.ocr.self-test:true}") boolean selfTestEnabled) {
        this.configuredPath = configuredPath == null ? "" : configuredPath.trim();
        this.languages = languages == null || languages.isBlank() ? "ara+eng" : languages.trim();
        this.selfTestEnabled = selfTestEnabled;
    }

    public String languages() {
        return languages;
    }

    /** The tessdata directory Tesseract should be pointed at. Never null. */
    public String datapath() {
        return resolveDatapath(configuredPath, System.getenv("TESSDATA_PREFIX"), candidatePaths())
                .map(Path::toString)
                .orElse(configuredPath.isBlank() ? CANDIDATE_DIRS.get(1) : configuredPath);
    }

    public Status status() {
        Status s = status;
        if (s == null) {
            s = probe();
            status = s;
        }
        return s;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void selfTest() {
        if (!selfTestEnabled) return;
        Status s = probe();
        status = s;
        if (s.ready()) {
            log.info("OCR self-test passed: tesseract {} · datapath={} · languages={} · smoke test read \"{}\"",
                    s.engineVersion(), s.datapath(), String.join("+", s.languages()), s.smokeText());
        } else {
            log.error("OCR self-test FAILED: {}. Document and PDF OCR will answer 503 "
                            + "\"OCR engine is not available on this server\" until this is fixed. {}",
                    s.error(), INSTALL_HINT);
        }
    }

    /** Loads the native library, checks the language files, then OCRs a tiny rendered image. */
    Status probe() {
        List<String> langs = splitLanguages(languages);
        Optional<Path> dp = resolveDatapath(configuredPath, System.getenv("TESSDATA_PREFIX"), candidatePaths());

        String version;
        try {
            version = TessAPI.INSTANCE.TessVersion();
        } catch (Throwable t) {
            log.debug("Tesseract native library failed to load", t);
            return new Status(false, null, dp.map(Path::toString).orElse(null), langs, List.of(), null,
                    "native library did not load (" + describe(t) + ")");
        }

        if (dp.isEmpty()) {
            List<String> looked = new ArrayList<>();
            if (!configuredPath.isBlank()) looked.add(configuredPath);
            String env = System.getenv("TESSDATA_PREFIX");
            if (env != null && !env.isBlank()) looked.add(env);
            looked.addAll(CANDIDATE_DIRS);
            return new Status(true, version, null, langs, langs, null,
                    "no tessdata directory with .traineddata files found (looked in " + String.join(", ", looked) + ")");
        }

        Path dir = dp.get();
        List<String> missing = langs.stream()
                .filter(l -> !Files.isRegularFile(dir.resolve(l + ".traineddata")))
                .toList();
        if (!missing.isEmpty()) {
            return new Status(true, version, dir.toString(), langs, missing, null,
                    "language files missing in " + dir + ": " + String.join(", ", missing)
                            + " (apt install tesseract-ocr-" + String.join(" tesseract-ocr-", missing) + ")");
        }

        String smoke;
        try {
            smoke = smokeOcr(dir.toString(), languages);
        } catch (Throwable t) {
            log.debug("OCR smoke test failed", t);
            return new Status(true, version, dir.toString(), langs, List.of(), null,
                    "engine loaded but OCR of a test image failed (" + describe(t) + ")");
        }
        if (smoke.isBlank()) {
            return new Status(true, version, dir.toString(), langs, List.of(), "",
                    "engine loaded but OCR of a test image returned no text");
        }
        return new Status(true, version, dir.toString(), langs, List.of(), smoke, null);
    }

    /** Renders {@link #SMOKE_TEXT} and runs the full tess4j path over it. */
    static String smokeOcr(String datapath, String languages) throws Exception {
        BufferedImage img = new BufferedImage(260, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 36));
            g.drawString(SMOKE_TEXT, 20, 54);
        } finally {
            g.dispose();
        }
        Tesseract t = new Tesseract();
        t.setDatapath(datapath);
        t.setLanguage(languages);
        String text = t.doOCR(img);
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    /**
     * First directory, in priority order, that holds at least one {@code .traineddata} file.
     * A directory whose {@code tessdata} child holds them is accepted too, so pointing the
     * config at the Tesseract prefix rather than the tessdata folder still works.
     */
    static Optional<Path> resolveDatapath(String configured, String envPrefix, List<Path> candidates) {
        List<Path> ordered = new ArrayList<>();
        if (configured != null && !configured.isBlank()) ordered.add(Path.of(configured.trim()));
        if (envPrefix != null && !envPrefix.isBlank()) ordered.add(Path.of(envPrefix.trim()));
        ordered.addAll(candidates);
        for (Path p : ordered) {
            if (hasTrainedData(p)) return Optional.of(p);
            Path child = p.resolve("tessdata");
            if (hasTrainedData(child)) return Optional.of(child);
        }
        return Optional.empty();
    }

    static boolean hasTrainedData(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return false;
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(f -> f.getFileName().toString().endsWith(".traineddata"));
        } catch (IOException e) {
            return false;
        }
    }

    static List<String> splitLanguages(String spec) {
        return Arrays.stream(spec.split("\\+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** The client-facing 503 text: what broke, plus what to do about it. */
    public static String unavailableMessage(Throwable cause) {
        return "OCR engine is not available on this server (" + describe(cause) + "). " + INSTALL_HINT;
    }

    static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = root.getMessage();
        String text = root.getClass().getSimpleName()
                + (msg == null || msg.isBlank() ? "" : ": " + msg.trim().replaceAll("\\s+", " "));
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    private static List<Path> candidatePaths() {
        return CANDIDATE_DIRS.stream().map(Path::of).toList();
    }
}
