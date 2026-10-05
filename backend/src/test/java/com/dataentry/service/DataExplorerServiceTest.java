package com.dataentry.service;

import com.dataentry.dto.DataExplorerDtos;
import com.dataentry.model.Team;
import com.dataentry.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DataExplorerService.class)
class DataExplorerServiceTest {

    @Autowired DataExplorerService service;
    @Autowired TeamRepository teamRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    @Test
    void search_keepsLegacyTicketWhenRequiredRelationsAreMissing() throws Exception {
        Team team = teamRepository.saveAndFlush(Team.builder()
                .slug("legacy-team")
                .name("Legacy team")
                .active(true)
                .build());
        em.clear();

        boolean postgres;
        try (var connection = jdbc.getDataSource().getConnection()) {
            postgres = connection.getMetaData().getDatabaseProductName().equals("PostgreSQL");
        }
        // Only synthetic test data: emulate imported legacy orphan references.
        jdbc.execute(postgres ? "SET LOCAL session_replication_role = replica" : "SET REFERENTIAL_INTEGRITY FALSE");
        jdbc.update("""
                INSERT INTO tickets
                    (team_id, submitted_by_id, department_id, title, content, submitted_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                team.getId(), 999_001L, 999_002L, "Legacy ticket", "Historical content",
                Timestamp.from(Instant.parse("2026-08-25T10:00:00Z")), "IN_PROGRESS");
        jdbc.execute(postgres ? "SET LOCAL session_replication_role = origin" : "SET REFERENTIAL_INTEGRITY TRUE");

        DataExplorerDtos.Page page = service.search(
                new DataExplorerService.Filters(team.getId(), null, null, null, null, null),
                null, 50, null);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        DataExplorerDtos.Row row = page.items().get(0);
        assertThat(row.teamId()).isEqualTo(team.getId());
        assertThat(row.teamName()).isEqualTo("Legacy team");
        assertThat(row.submittedByUserId()).isEqualTo(999_001L);
        assertThat(row.submittedByUsername()).isNull();
        assertThat(row.departmentId()).isEqualTo(999_002L);
        assertThat(row.departmentName()).isNull();

        DataExplorerDtos.Row byId = service.byId(row.id(), null);
        assertThat(byId.id()).isEqualTo(row.id());
        assertThat(byId.submittedByUserId()).isEqualTo(999_001L);
        assertThat(byId.submittedByUsername()).isNull();
        assertThat(byId.departmentId()).isEqualTo(999_002L);
        assertThat(byId.departmentName()).isNull();
    }
    @Test
    void statsCountEveryPageClassifyFilesAndShareAllExplorerFilters() {
        var a = teamRepository.saveAndFlush(Team.builder().slug("analytics-a").name("Analytics A").build());
        var b = teamRepository.saveAndFlush(Team.builder().slug("analytics-b").name("Analytics B").build());
        var user = com.dataentry.model.User.builder().username("analytics-user").passwordHash("fixture")
                .role(com.dataentry.model.Role.USER).team(a).build(); em.persist(user);
        var otherUser = com.dataentry.model.User.builder().username("analytics-other").passwordHash("fixture")
                .role(com.dataentry.model.Role.USER).team(b).build(); em.persist(otherUser);
        var department = com.dataentry.model.Department.builder().team(a).name("Analytics").build(); em.persist(department);
        var project = com.dataentry.model.Project.builder().team(a).name("Analytics project").build(); em.persist(project);
        com.dataentry.model.Ticket first = null;
        for (int i = 0; i < 55; i++) {
            var ticket = com.dataentry.model.Ticket.builder().team(a).submittedBy(user).department(department)
                    .project(project).title("Analytics Alpha").content("Fixture text")
                    .submittedAt(Instant.parse(i == 0 ? "2026-09-13T12:00:00Z" : "2026-09-12T12:00:00Z")).build();
            em.persist(ticket); attachment(ticket, "REPORT.PDF", "application/octet-stream");
            if (i == 0) first = ticket;
        }
        attachment(first, "memo.docx", "application/pdf"); // Extension wins; never counted in two buckets.
        attachment(first, "sheet.xlsx", null);
        attachment(first, "photo.PNG", "image/png");
        attachment(first, "deck.pptx", null);
        attachment(first, "archive.zip", "application/zip");
        attachment(first, "extensionless", "application/msword");
        em.persist(com.dataentry.model.Ticket.builder().team(a).submittedBy(user).department(department)
                .project(project).title("No attachment").content("Empty file list").build());
        var foreign = com.dataentry.model.Ticket.builder().team(b).submittedBy(otherUser).department(department)
                .title("Other team").content("Fixture").build(); em.persist(foreign);
        attachment(foreign, "other.pdf", "application/pdf"); em.flush(); em.clear();

        var filters = new DataExplorerService.Filters(a.getId(), null, null, null, null, null);
        assertThat(service.search(filters, null, 50, null).items()).hasSize(50);
        var stats = service.stats(filters);
        assertThat(stats.totalTickets()).isEqualTo(56);
        assertThat(stats.ticketsWithFiles()).isEqualTo(55);
        assertThat(stats.totalFiles()).isEqualTo(61);
        assertThat(stats.totalBytes()).isEqualTo(6100);
        assertThat(stats.pdfFiles()).isEqualTo(55);
        assertThat(stats.wordFiles()).isEqualTo(2);
        assertThat(stats.spreadsheetFiles()).isEqualTo(1);
        assertThat(stats.imageFiles()).isEqualTo(1);
        assertThat(stats.presentationFiles()).isEqualTo(1);
        assertThat(stats.otherFiles()).isEqualTo(1);
        assertThat(service.stats(new DataExplorerService.Filters(null, project.getId(), null, null, null, null)).totalFiles()).isEqualTo(61);
        assertThat(service.stats(new DataExplorerService.Filters(null, null, user.getId(), null, null, null)).totalFiles()).isEqualTo(61);
        assertThat(service.stats(new DataExplorerService.Filters(b.getId(), null, null, null, null, null)).totalFiles()).isEqualTo(1);
        var selected = service.stats(new DataExplorerService.Filters(a.getId(), project.getId(), user.getId(),
                Instant.parse("2026-09-13T00:00:00Z"), Instant.parse("2026-09-14T00:00:00Z"), "ALPHA"));
        assertThat(selected.totalTickets()).isEqualTo(1);
        assertThat(selected.totalFiles()).isEqualTo(7);
        assertThat(selected.totalBytes()).isEqualTo(700);
        var empty = service.stats(new DataExplorerService.Filters(null, null, null, null, null, "missing-fixture"));
        assertThat(empty).isEqualTo(new DataExplorerDtos.Stats(0,0,0,0,0,0,0,0,0,0));
    }

    private void attachment(com.dataentry.model.Ticket ticket, String filename, String mime) {
        em.persist(com.dataentry.model.TicketDocument.builder().ticket(ticket).name(filename)
                .originalFilename(filename).contentType(mime).sizeBytes(100).storagePath("fixture-only/" + filename).build());
    }

    @Test
    void departmentFilterIsSharedByRowsStatsAndDownloadManifest() {
        var team = teamRepository.saveAndFlush(Team.builder().slug("department-filter").name("Department filter").build());
        var user = com.dataentry.model.User.builder().username("department-user").passwordHash("fixture")
                .role(com.dataentry.model.Role.USER).team(team).build(); em.persist(user);
        var project = com.dataentry.model.Project.builder().team(team).name("Department project").build(); em.persist(project);
        var selected = com.dataentry.model.Department.builder().team(team).project(project).name("Selected").build(); em.persist(selected);
        var other = com.dataentry.model.Department.builder().team(team).project(project).name("Other").build(); em.persist(other);
        var ticket = com.dataentry.model.Ticket.builder().team(team).submittedBy(user).project(project).department(selected)
                .title("Mixed documents").content("Fixture").build(); em.persist(ticket);
        attachment(ticket, "report.pdf", "application/pdf");
        attachment(ticket, "report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        var excluded = com.dataentry.model.Ticket.builder().team(team).submittedBy(user).project(project).department(other)
                .title("Other department").content("Fixture").build(); em.persist(excluded);
        attachment(excluded, "excluded.pdf", "application/pdf"); em.flush(); em.clear();

        var filters = new DataExplorerService.Filters(team.getId(), project.getId(), null, null, null, null, selected.getId());
        assertThat(service.search(filters, null, 50, null).items()).extracting(DataExplorerDtos.Row::id).containsExactly(ticket.getId());
        var stats = service.stats(filters);
        assertThat(stats.totalTickets()).isEqualTo(1);
        assertThat(stats.totalFiles()).isEqualTo(2);
        assertThat(stats.pdfFiles()).isEqualTo(1);
        assertThat(stats.wordFiles()).isEqualTo(1);
        var manifest = service.manifest(filters, true);
        assertThat(manifest.files()).extracting(DataExplorerDtos.ManifestEntry::originalFilename).containsExactly("report.pdf", "report.docx");
        assertThat(manifest.tickets()).extracting(DataExplorerDtos.ManifestTicket::id).containsExactly(ticket.getId());
        assertThat(service.facets().departments()).contains(new DataExplorerDtos.DepartmentNamed(
                selected.getId(), selected.getName(), project.getId(), team.getId()));
        assertThat(service.manifest(new DataExplorerService.Filters(null, null, null, null, null, null, -1L), false).files()).isEmpty();
    }
}
