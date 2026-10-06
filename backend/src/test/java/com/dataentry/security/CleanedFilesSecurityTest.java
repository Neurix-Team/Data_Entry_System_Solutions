package com.dataentry.security;

import com.dataentry.model.*;
import com.dataentry.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.config.import=", "app.seed.enabled=false", "app.translation.base-url=",
        "app.security.api-rate.per-minute=10000", "management.server.port="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CleanedFilesSecurityTest {
    private static final Path FILES = temporaryDirectory();
    private static final byte[] CLEAN = "Cleaned Arabic data: مرحبا\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired ProjectRepository projects;
    @Autowired DepartmentRepository departments;
    @Autowired CleanedFileRepository files;
    @Autowired JwtService jwt;
    User superAdmin, admin, user;
    Project project;
    Department department, otherDepartment;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.cleaned-files.dir", FILES::toString);
        registry.add("app.cleaned-files.max-file-bytes", () -> 1048576);
    }
    @BeforeEach
    void fixtures() {
        TenantContext.clear(); org.springframework.security.core.context.SecurityContextHolder.clearContext();
        files.deleteAll();
        String id = UUID.randomUUID().toString();
        var team = teams.save(Team.builder().slug("clean-" + id).name("Clean fixture").build());
        superAdmin = users.save(User.builder().username("super-" + id).passwordHash("fixture-only").role(Role.SUPER_ADMIN).build());
        admin = users.save(User.builder().username("admin-" + id).passwordHash("fixture-only").role(Role.ADMIN).team(team).build());
        user = users.save(User.builder().username("user-" + id).passwordHash("fixture-only").role(Role.USER).team(team).build());
        project = projects.save(Project.builder().team(team).name("Clean project " + id).build());
        var other = projects.save(Project.builder().team(team).name("Other project " + id).build());
        department = departments.save(Department.builder().team(team).project(project).name("Selected " + id).build());
        otherDepartment = departments.save(Department.builder().team(team).project(other).name("Other " + id).build());
    }

    @Test
    void uploadDownloadAndStatusUpdatesPreserveBytesAndRecordDates() throws Exception {
        var uploaded = upload("clean.md", CLEAN, department.getId(), metadata(), superAdmin, 201);
        long id = uploaded.path("id").asLong();
        assertThat(uploaded.path("status").asText()).isEqualTo("READY");
        assertThat(uploaded.path("sha256").asText()).hasSize(64);
        mvc.perform(get("/api/super/cleaned-files/" + id + "/download").header("Authorization", bearer(superAdmin)))
                .andExpect(status().isOk()).andExpect(content().bytes(CLEAN))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Content-Type", "application/octet-stream"));
        JsonNode started = update(id, uploaded.path("version").asLong(), "IN_PROGRESS", 200);
        assertThat(started.path("startedAt").asText()).isNotBlank();
        JsonNode complete = update(id, started.path("version").asLong(), "COMPLETED", 200);
        assertThat(complete.path("completedAt").asText()).isNotBlank();
        update(id, uploaded.path("version").asLong(), "READY", 409);
        JsonNode reopened = update(id, complete.path("version").asLong(), "READY", 200);
        assertThat(reopened.path("completedAt").isNull()).isTrue();
        assertThat(reopened.path("startedAt").isNull()).isTrue();
    }

    @Test
    void onlySuperAdminCanListUploadUpdateOrDownload() throws Exception {
        var uploaded = upload("clean.jsonl", CLEAN, department.getId(), metadata(), superAdmin, 201);
        long id = uploaded.path("id").asLong();
        for (User actor : List.of(admin, user)) {
            mvc.perform(get("/api/super/cleaned-files").header("Authorization", bearer(actor))).andExpect(status().isForbidden());
            mvc.perform(get("/api/super/cleaned-files/options").header("Authorization", bearer(actor))).andExpect(status().isForbidden());
            mvc.perform(get("/api/super/cleaned-files/" + id + "/download").header("Authorization", bearer(actor))).andExpect(status().isForbidden());
            upload("clean.md", CLEAN, department.getId(), metadata(), actor, 403);
            mvc.perform(put("/api/super/cleaned-files/" + id).header("Authorization", bearer(actor)).contentType("application/json")
                    .content(json.writeValueAsBytes(Map.of("metadata", metadata(), "status", "COMPLETED", "version", 0)))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/super/cleaned-files")).andExpect(status().isForbidden());
        mvc.perform(get("/api/super/cleaned-files/" + id + "/download")).andExpect(status().isForbidden());
    }

    @Test
    void mismatchedDepartmentInvalidDatesAndUnsafeContentAreRejectedWithoutRows() throws Exception {
        upload("clean.txt", CLEAN, otherDepartment.getId(), metadata(), superAdmin, 400);
        var invalidDates = new HashMap<>(metadata()); invalidDates.put("dueOn", LocalDate.now().minusDays(3).toString());
        upload("clean.txt", CLEAN, department.getId(), invalidDates, superAdmin, 400);
        upload("run.exe", CLEAN, department.getId(), metadata(), superAdmin, 400);
        upload("fake.txt", "<html><body><script>alert(1)</script></body></html>".getBytes(), department.getId(), metadata(), superAdmin, 400);
        upload("empty.txt", new byte[0], department.getId(), metadata(), superAdmin, 400);
        upload("large.txt", new byte[1048577], department.getId(), metadata(), superAdmin, 413);
        assertThat(files.count()).isZero();
    }

    @Test
    void filtersAndPaginationCountsCoverAllMatchingFiles() throws Exception {
        var uploaded = upload("clean.txt", CLEAN, department.getId(), metadata(), superAdmin, 201);
        for (int i = 0; i < 31; i++) {
            var file = new CleanedFile(); file.setProject(project); file.setDepartment(department);
            file.setTitle("Second batch " + i); file.setOriginalFilename("batch.txt"); file.setStorageKey(UUID.randomUUID().toString());
            file.setSizeBytes(1); file.setSha256("0".repeat(64)); file.setCleanedOn(LocalDate.now().minusDays(1));
            file.setUploadedBy(superAdmin.getUsername()); file.setStatus(CleanedFile.Status.COMPLETED); files.save(file);
        }
        var page = mvc.perform(get("/api/super/cleaned-files").param("projectId", project.getId().toString())
                        .param("departmentId", department.getId().toString()).header("Authorization", bearer(superAdmin)))
                .andExpect(status().isOk()).andReturn();
        JsonNode rows = json.readTree(page.getResponse().getContentAsByteArray());
        assertThat(rows.path("total").asLong()).isEqualTo(32); assertThat(rows.path("items").size()).isEqualTo(30);
        assertThat(rows.path("completed").asLong()).isEqualTo(31); assertThat(rows.path("ready").asLong()).isEqualTo(1);
        mvc.perform(get("/api/super/cleaned-files").param("page", "1").param("status", "READY")
                        .header("Authorization", bearer(superAdmin))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/super/cleaned-files").param("search", "Clean batch").param("from", LocalDate.now().minusDays(1).toString())
                        .param("to", LocalDate.now().minusDays(1).toString()).header("Authorization", bearer(superAdmin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(uploaded.path("id").asLong())).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/super/cleaned-files").param("departmentId", otherDepartment.getId().toString())
                        .header("Authorization", bearer(superAdmin))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void deletingSourceProjectOrDepartmentCannotDestroyCleanedOutputs() throws Exception {
        var uploaded = upload("clean.txt", CLEAN, department.getId(), metadata(), superAdmin, 201);
        mvc.perform(delete("/api/admin/departments/" + department.getId()).header("Authorization", bearer(superAdmin)))
                .andExpect(status().isConflict());
        project.setDeletedAt(java.time.Instant.now()); projects.save(project);
        mvc.perform(delete("/api/admin/recycle-bin/projects/" + project.getId()).header("Authorization", bearer(superAdmin)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/super/cleaned-files/" + uploaded.path("id").asLong() + "/download")
                        .header("Authorization", bearer(superAdmin))).andExpect(status().isOk()).andExpect(content().bytes(CLEAN));
        assertThat(files.count()).isEqualTo(1);
    }

    @Test
    void invalidStatusesAndUnknownDownloadsReturnClientErrors() throws Exception {
        mvc.perform(get("/api/super/cleaned-files").param("status", "UNSUPPORTED").header("Authorization", bearer(superAdmin))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/super/cleaned-files/9999999/download").header("Authorization", bearer(superAdmin))).andExpect(status().isNotFound());
        mvc.perform(get("/api/super/cleaned-files/options").header("Authorization", bearer(superAdmin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maxFileBytes").value(1048576));
    }

    private Map<String, String> metadata() {
        return Map.of("title", "Clean batch", "cleanedOn", LocalDate.now().minusDays(1).toString(),
                "dueOn", LocalDate.now().plusDays(1).toString(), "sourceReference", "Export batch 01", "notes", "Reviewed fixture sample");
    }
    private JsonNode upload(String name, byte[] content, Long departmentId, Map<String, String> metadata, User actor, int expected) throws Exception {
        var response = mvc.perform(multipart("/api/super/cleaned-files")
                .file(new MockMultipartFile("file", name, "application/octet-stream", content))
                .file(new MockMultipartFile("metadata", "metadata.json", "application/json", json.writeValueAsBytes(metadata)))
                .param("projectId", project.getId().toString()).param("departmentId", departmentId.toString())
                .header("Authorization", bearer(actor))).andExpect(status().is(expected)).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray());
    }
    private JsonNode update(long id, long version, String status, int expected) throws Exception {
        var response = mvc.perform(put("/api/super/cleaned-files/" + id).header("Authorization", bearer(superAdmin)).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("metadata", metadata(), "status", status, "version", version))))
                .andExpect(status().is(expected)).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray());
    }
    private String bearer(User actor) { return "Bearer " + jwt.generateToken(actor.getUsername(), actor.getRole().name(), actor.getId(), actor.getTeam() == null ? null : actor.getTeam().getId(), actor.getTokenVersion()); }
    private static Path temporaryDirectory() { try { return Files.createTempDirectory("cleaned-files-fixture-"); } catch (java.io.IOException ex) { throw new ExceptionInInitializerError(ex); } }
    @AfterAll static void cleanup() throws java.io.IOException { try (var paths = Files.walk(FILES)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
}
