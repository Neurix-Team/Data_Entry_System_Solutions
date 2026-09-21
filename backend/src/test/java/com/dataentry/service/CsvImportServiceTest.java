package com.dataentry.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The RFC 4180 parser is the only thing between an admin's spreadsheet and the
 * database, so its edge cases get their own tests: quotes, embedded commas and
 * newlines, doubled quotes, CRLF, BOM-less headers and a missing final newline.
 */
class CsvImportServiceTest {

    @Test
    void parsesPlainRowsAndHeaderCaseInsensitively() {
        List<List<String>> rows = CsvImportService.parseCsv("Title,CONTENT\n\"A\",B\n");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly("Title", "CONTENT");
        assertThat(rows.get(1)).containsExactly("A", "B");
    }

    @Test
    void handlesQuotedCommasQuotesAndNewlines() {
        String csv = "\"Say \"\"hi\"\", please\",plain\n\"line1\nline2\",end";
        List<List<String>> rows = CsvImportService.parseCsv(csv);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly("Say \"hi\", please", "plain");
        assertThat(rows.get(1)).containsExactly("line1\nline2", "end");
    }

    @Test
    void handlesCrLfAndMissingFinalNewline() {
        List<List<String>> rows = CsvImportService.parseCsv("a,b\r\nc,d");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("c", "d");
    }

    @Test
    void emptyTrailingCellIsPreserved() {
        List<List<String>> rows = CsvImportService.parseCsv("a,\nb,c");
        assertThat(rows.get(0)).containsExactly("a", "");
        assertThat(rows.get(1)).containsExactly("b", "c");
    }
}