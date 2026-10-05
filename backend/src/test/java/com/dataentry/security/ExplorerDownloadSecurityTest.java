package com.dataentry.security;

import com.dataentry.model.*;
import com.dataentry.repository.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.config.import=", "app.seed.enabled=false", "app.translation.base-url=",
        "app.security.api-rate.per-minute=10000", "management.server.port=",
        "spring.mvc.async.request-timeout=6h"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExplorerDownloadSecurityTest {
    private static final Path FILES = temporaryDirectory();
    private static final byte[] PDF = "%PDF-1.4\nfixture-pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] WORD = "fixture-word-document".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired ProjectRepository projects;
    @Autowired DepartmentRepository departments;
    @Autowired TicketRepository tickets;
    @Autowired TicketDocumentRepository documents;
    @Autowired JwtService jwt;
    User superAdmin, admin;
    Department selected;

    @DynamicPropertySource
    static void attachments(DynamicPropertyRegistry registry) {
        registry.add("app.attachments.dir", FILES::toString);
        registry.add("app.uploads.incoming-dir", () -> FILES.resolve("incoming").toString());
    }

    @BeforeEach
    void fixtures() throws IOException {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        String id = UUID.randomUUID().toString();
        var team = teams.save(Team.builder().slug("download-" + id).name("Download fixture").build());
        superAdmin = users.save(User.builder().username("super-" + id).passwordHash("fixture-only").role(Role.SUPER_ADMIN).build());
        admin = users.save(User.builder().username("admin-" + id).passwordHash("fixture-only").role(Role.ADMIN).team(team).build());
        var project = projects.save(Project.builder().team(team).name("Project " + id).build());
        selected = departments.save(Department.builder().team(team).project(project).name("Selected " + id).build());
        var other = departments.save(Department.builder().team(team).project(project).name("Other " + id).build());
        var ticket = tickets.save(Ticket.builder().team(team).project(project).department(selected).submittedBy(admin)
                .title("Mixed files").content("Fixture").build());
        var excluded = tickets.save(Ticket.builder().team(team).project(project).department(other).submittedBy(admin)
                .title("Excluded files").content("Fixture").build());
        Files.write(FILES.resolve("fixture.pdf"), PDF);
        Files.write(FILES.resolve("fixture.docx"), WORD);
        document(ticket, "report.pdf", "application/pdf", "fixture.pdf", PDF.length);
        document(ticket, "report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "fixture.docx", WORD.length);
        document(excluded, "excluded.pdf", "application/pdf", "fixture.pdf", PDF.length);
    }

    @Test
    void allFilesZipCompletesAsyncDispatchAndContainsBothFormatsFromSelectedDepartment() throws Exception {
        var result = mvc.perform(get("/api/super/data/archive").param("departmentId", selected.getId().toString())
                        .param("fileType", "all").header("Authorization", bearer(superAdmin)))
                .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
        assertThat(result.getRequest().getAsyncContext().getTimeout()).isGreaterThan(Duration.ofMinutes(30).toMillis());
        result.getAsyncResult(10_000);
        var completed = mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentType("application/zip")).andReturn();
        var files = unzip(completed.getResponse().getContentAsByteArray());
        assertThat(files).hasSize(2);
        assertThat(files.entrySet()).anySatisfy(entry -> {
            assertThat(entry.getKey()).endsWith("/report.pdf"); assertThat(entry.getValue()).isEqualTo(PDF);
        }).anySatisfy(entry -> {
            assertThat(entry.getKey()).endsWith("/report.docx"); assertThat(entry.getValue()).isEqualTo(WORD);
        });
        assertThat(files.keySet()).noneMatch(path -> path.contains("excluded"));
    }

    @Test
    void cookieAuthenticationAlsoSurvivesArchiveAsyncDispatch() throws Exception {
        var result = mvc.perform(get("/api/super/data/archive").param("departmentId", selected.getId().toString())
                        .param("fileType", "pdf").cookie(new jakarta.servlet.http.Cookie(JwtAuthFilter.AUTH_COOKIE, token(superAdmin))))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(10_000);
        var completed = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn();
        assertThat(unzip(completed.getResponse().getContentAsByteArray()).keySet()).singleElement().asString().endsWith(".pdf");
    }

    @Test
    void ordinaryAdminAndAnonymousRequestCannotStartSuperAdminDownload() throws Exception {
        mvc.perform(get("/api/super/data/archive").header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden()).andExpect(request().asyncNotStarted());
        mvc.perform(get("/api/super/data/archive"))
                .andExpect(status().isForbidden()).andExpect(request().asyncNotStarted());
    }

    private void document(Ticket ticket, String filename, String mime, String storage, long size) {
        documents.save(TicketDocument.builder().ticket(ticket).name(filename).originalFilename(filename)
                .contentType(mime).storagePath(storage).sizeBytes(size).build());
    }

    private String token(User user) {
        return jwt.generateToken(user.getUsername(), user.getRole().name(), user.getId(),
                user.getTeam() == null ? null : user.getTeam().getId(), user.getTokenVersion());
    }

    private String bearer(User user) { return "Bearer " + token(user); }

    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                files.put(entry.getName(), zip.readAllBytes());
            }
        }
        return files;
    }

    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("explorer-download-fixture-"); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    @AfterAll
    static void cleanup() throws IOException {
        try (var paths = Files.walk(FILES)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
