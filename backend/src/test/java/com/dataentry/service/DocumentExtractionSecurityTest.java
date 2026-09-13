package com.dataentry.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DocumentExtractionSecurityTest {
    @TempDir Path temp;

    private DocumentExtractionService service(int limit) {
        return new DocumentExtractionService(mock(PdfExtractionService.class), limit,
                mock(OfficeImageExtractor.class),
                new ExtractionStagingService(temp.resolve("staging").toString(),24), mock(OcrGate.class));
    }

    @Test void oversizedTextRemainsBoundedWithoutUnlimitedRetry() {
        var input=new MockMultipartFile("file","large.txt","text/plain",
                "a ".repeat(4096).getBytes(StandardCharsets.UTF_8));
        var result=service(128).extract(input);
        assertThat(result.characters()).isBetween(1,128);
        assertThat(result.truncated()).isTrue();
    }

    @Test void arabicTextStillExtracts() {
        String text="مستند عربي لاختبار حماية معالجة الملفات";
        var result=service(1024).extract(new MockMultipartFile("file","arabic.txt","text/plain",
                text.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.text()).contains("مستند عربي");
    }

    @Test void externalXmlEntityCannotReadLocalFile() throws Exception {
        Path secret=temp.resolve("fixture.txt");Files.writeString(secret,"FIXTURE_NOT_FOR_EXTRACTION");
        String xml="<?xml version=\"1.0\"?><!DOCTYPE doc [<!ENTITY ext SYSTEM \""
                +secret.toUri()+"\">]><doc>&ext;</doc>";
        try {
            var result=service(1024).extract(new MockMultipartFile("file","input.xml","application/xml",
                    xml.getBytes(StandardCharsets.UTF_8)));
            assertThat(result.text()).doesNotContain("FIXTURE_NOT_FOR_EXTRACTION");
        } catch (ResponseStatusException rejected) {
            assertThat(rejected.getStatusCode().value()).isIn(400,422);
        }
    }
    @Test void arabicDocxStillExtractsAfterParserUpgrade() throws Exception {
        byte[] content;
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument();
             var output = new java.io.ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("مستند عربي آمن مع English text");
            document.write(output); content = output.toByteArray();
        }
        var result = service(1024).extract(new MockMultipartFile("file", "arabic.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", content));
        assertThat(result.text()).contains("مستند عربي", "English text");
    }

    @Test void officeImageBudgetSkipsOversizedEntryAndKeepsValidImage() throws Exception {
        Path archive = temp.resolve("images.docx");
        var picture = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var imageBytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(picture, "png", imageBytes);
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("word/media/oversized.png"));
            byte[] block = new byte[1024];
            for (int i = 0; i < 20 * 1024 + 1; i++) zip.write(block);
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("word/media/valid.png"));
            zip.write(imageBytes.toByteArray()); zip.closeEntry();
        }
        Path output = temp.resolve("images");
        var images = new OfficeImageExtractor(64, 40).extractInto(archive.toFile(), output);
        assertThat(images).hasSize(1);
        assertThat(images.get(0).width()).isEqualTo(64);
        assertThat(Files.size(output.resolve(images.get(0).filename()))).isEqualTo(imageBytes.size());
        try (var files = Files.list(output)) { assertThat(files.count()).isEqualTo(1); }
    }
}
