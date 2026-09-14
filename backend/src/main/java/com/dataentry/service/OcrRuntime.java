package com.dataentry.service;

import net.sourceforge.tess4j.TessAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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
            "Inside Docker: rebuild the image (docker compose build backend && docker compose up -d backend). "
            + "Bare metal: apt install tesseract-ocr tesseract-ocr-eng tesseract-ocr-ara libtesseract-dev, "
            + "then set APP_OCR_TESSDATA_PATH if tessdata is somewhere unusual. See RUNBOOK.md, "
            + "\"OCR engine is not available\".";

    public record Status(boolean engineLoaded,
                         String engineVersion,
                         String datapath,
                         List<String> languages,
                         List<String> missingLanguages,
                         String error) {
        public boolean ready() {
            return engineLoaded && datapath != null && missingLanguages.isEmpty();
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
            log.info("OCR self-test passed: tesseract {} · datapath={} · languages={}",
                    s.engineVersion(), s.datapath(), String.join("+", s.languages()));
        } else {
            log.error("OCR self-test FAILED: {}. Document and PDF OCR will answer 503 "
                            + "\"OCR engine is not available on this server\" until this is fixed. {}",
                    s.error(), INSTALL_HINT);
        }
    }

    /** Loads the native library (cheap: version string only) and checks the language files. */
    Status probe() {
        List<String> langs = splitLanguages(languages);
        Optional<Path> dp = resolveDatapath(configuredPath, System.getenv("TESSDATA_PREFIX"), candidatePaths());

        String version;
        try {
            version = TessAPI.INSTANCE.TessVersion();
        } catch (Throwable t) {
            log.debug("Tesseract native library failed to load", t);
            return new Status(false, null, dp.map(Path::toString).orElse(null), langs, langs,
                    "native library did not load (" + describe(t) + ")");
        }

        if (dp.isEmpty()) {
            List<String> looked = new ArrayList<>();
            if (!configuredPath.isBlank()) looked.add(configuredPath);
            String env = System.getenv("TESSDATA_PREFIX");
            if (env != null && !env.isBlank()) looked.add(env);
            looked.addAll(CANDIDATE_DIRS);
            return new Status(true, version, null, langs, langs,
                    "no tessdata directory with .traineddata files found (looked in " + String.join(", ", looked) + ")");
        }

        Path dir = dp.get();
        List<String> missing = langs.stream()
                .filter(l -> !Files.isRegularFile(dir.resolve(l + ".traineddata")))
                .toList();
        String error = missing.isEmpty() ? null
                : "language files missing in " + dir + ": " + String.join(", ", missing)
                  + " (apt install tesseract-ocr-" + String.join(" tesseract-ocr-", missing) + ")";
        return new Status(true, version, dir.toString(), langs, missing, error);
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
        String text = root.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg.trim());
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    private static List<Path> candidatePaths() {
        return CANDIDATE_DIRS.stream().map(Path::of).toList();
    }
}
