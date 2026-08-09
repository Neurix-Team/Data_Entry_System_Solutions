package com.dataentry.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfOcrServiceTest {

    @Test
    void containsRtl_returnsFalseForLatinText() {
        assertThat(PdfOcrService.containsRtl("Hello world, this is English.")).isFalse();
    }

    @Test
    void containsRtl_returnsFalseForEmptyOrNull() {
        assertThat(PdfOcrService.containsRtl(null)).isFalse();
        assertThat(PdfOcrService.containsRtl("")).isFalse();
    }

    @Test
    void containsRtl_returnsTrueForArabicText() {
        // Repeat enough Arabic characters to exceed the 20-char RTL threshold
        String arabic = "مرحبا بكم في نظام إدارة إدخال البيانات، هذا نص عربي طويل بما يكفي.";
        assertThat(PdfOcrService.containsRtl(arabic)).isTrue();
    }

    @Test
    void cleanOcrOutput_stripsMostlySymbolLines() {
        String noisy = "This is a real sentence.\n" +
                "|||||_____====||\n" +
                "Second real line.";
        String cleaned = PdfOcrService.cleanOcrOutput(noisy);
        assertThat(cleaned).contains("real sentence");
        assertThat(cleaned).contains("Second real line");
        assertThat(cleaned).doesNotContain("|||||");
    }

    @Test
    void cleanOcrOutput_keepsPunctuatedSentences() {
        String text = "Question: what is this? Answer: it's a test.";
        String cleaned = PdfOcrService.cleanOcrOutput(text);
        assertThat(cleaned).isEqualTo(text);
    }

    @Test
    void cleanOcrOutput_collapsesConsecutiveBlankLines() {
        String text = "Line one.\n\n\n\nLine two.";
        String cleaned = PdfOcrService.cleanOcrOutput(text);
        assertThat(cleaned).isEqualTo("Line one.\n\nLine two.");
    }

    @Test
    void cleanOcrOutput_dropsStrayDecorativeSymbols() {
        // Leading `~` and trailing `<` are stripped by the symbol regex;
        // they become empty lines and get dropped.
        String text = "~\nA valid line here.\n<";
        String cleaned = PdfOcrService.cleanOcrOutput(text);
        assertThat(cleaned).isEqualTo("A valid line here.");
    }
}
