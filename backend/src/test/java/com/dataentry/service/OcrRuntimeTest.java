package com.dataentry.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OcrRuntimeTest {

    @TempDir Path tmp;

    private Path tessdata(String name, String... languages) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve(name));
        for (String l : languages) Files.writeString(dir.resolve(l + ".traineddata"), "x");
        return dir;
    }

    @Test
    void configuredPathWinsWhenItHoldsLanguageFiles() throws IOException {
        Path configured = tessdata("custom", "eng");
        Path fallback = tessdata("five/tessdata", "eng", "ara");

        Optional<Path> picked = OcrRuntime.resolveDatapath(configured.toString(), null, List.of(fallback));

        assertThat(picked).contains(configured);
    }

    @Test
    void emptyConfiguredPathFallsThroughEnvThenCandidates() throws IOException {
        Path env = tessdata("env-prefix", "eng");
        Path candidate = tessdata("candidate", "eng");

        assertThat(OcrRuntime.resolveDatapath("", env.toString(), List.of(candidate))).contains(env);
        assertThat(OcrRuntime.resolveDatapath("", null, List.of(candidate))).contains(candidate);
    }

    @Test
    void tesseractFiveLayoutIsFoundWhenTheOldFourPathIsMissing() throws IOException {
        Path five = tessdata("tesseract-ocr/5/tessdata", "eng", "ara");
        Path four = tmp.resolve("tesseract-ocr/4.00/tessdata");

        Optional<Path> picked = OcrRuntime.resolveDatapath(four.toString(), null, List.of(five));

        assertThat(picked).contains(five);
    }

    @Test
    void aPrefixDirectoryWithATessdataChildIsAccepted() throws IOException {
        Path child = tessdata("prefix/tessdata", "eng");

        Optional<Path> picked = OcrRuntime.resolveDatapath(child.getParent().toString(), null, List.of());

        assertThat(picked).contains(child);
    }

    @Test
    void directoriesWithoutTrainedDataAreSkipped() throws IOException {
        Path empty = Files.createDirectories(tmp.resolve("empty"));
        Path stale = tmp.resolve("does-not-exist");

        assertThat(OcrRuntime.resolveDatapath(stale.toString(), empty.toString(), List.of(empty))).isEmpty();
    }

    @Test
    void languageSpecSplitsOnPlus() {
        assertThat(OcrRuntime.splitLanguages("ara+eng")).containsExactly("ara", "eng");
        assertThat(OcrRuntime.splitLanguages(" eng ")).containsExactly("eng");
    }

    @Test
    void unavailableMessageNamesTheRootCauseAndTheFix() {
        Throwable cause = new NoClassDefFoundError("Could not initialize class net.sourceforge.tess4j.TessAPI");
        cause.initCause(new UnsatisfiedLinkError("Unable to load library 'tesseract': libtesseract.so: cannot open shared object file"));

        String msg = OcrRuntime.unavailableMessage(cause);

        assertThat(msg).startsWith("OCR engine is not available on this server (UnsatisfiedLinkError: Unable to load library 'tesseract'");
        assertThat(msg).contains("tesseract-ocr-ara").contains("RUNBOOK.md");
    }

    @Test
    void probeNeverThrowsEvenWithoutTesseractInstalled() {
        OcrRuntime runtime = new OcrRuntime(tmp.resolve("nowhere").toString(), "ara+eng", false);

        OcrRuntime.Status s = runtime.status();

        assertThat(s).isNotNull();
        assertThat(s.languages()).containsExactly("ara", "eng");
        if (!s.engineLoaded()) {
            assertThat(s.error()).contains("native library did not load");
            assertThat(s.ready()).isFalse();
        }
    }
}
