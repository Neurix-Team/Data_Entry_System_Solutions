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
    void search_keepsLegacyTicketWhenRequiredRelationsAreMissing() {
        Team team = teamRepository.saveAndFlush(Team.builder()
                .slug("legacy-team")
                .name("Legacy team")
                .active(true)
                .build());
        em.clear();

        // Reproduce data created by old SQLite installs before FK enforcement was enabled:
        // the ticket survived after its submitter and department rows disappeared.
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        jdbc.update("""
                INSERT INTO tickets
                    (team_id, submitted_by_id, department_id, title, content, submitted_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                team.getId(), 999_001L, 999_002L, "Legacy ticket", "Historical content",
                Timestamp.from(Instant.parse("2026-08-25T10:00:00Z")), "IN_PROGRESS");
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");

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
}
