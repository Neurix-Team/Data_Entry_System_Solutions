package com.dataentry.security;

import com.dataentry.model.*;
import com.dataentry.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Stage-4 tests: object-level authorization (IDOR) across the request-bearing
 * endpoints. Verifies that a same-role user in another team cannot read, mutate,
 * or attach to objects they do not own — even with valid credentials.
 */
@SpringBootTest(properties = {
    "spring.config.import=", "app.seed.enabled=false", "app.translation.base-url=",
    "app.security.api-rate.per-minute=10000", "management.server.port=",
    "app.attachments.dir=${java.io.tmpdir}/neurix-object-authz/attachments",
    "app.uploads.incoming-dir=${java.io.tmpdir}/neurix-object-authz/incoming",
    "app.pdf.extractions-dir=${java.io.tmpdir}/neurix-object-authz/extractions"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ObjectAuthorizationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired TicketRepository tickets;
    @Autowired DepartmentRepository departments;
    @Autowired SubcategoryRepository subcategories;
    @Autowired PasswordEncoder encoder;
    @Autowired com.dataentry.security.JwtService jwt;

    User agentA, agentB, adminB;
    Team teamA, teamB;
    Department deptA, deptB;
    Subcategory subA, subB;
    Ticket ticketOfB;
    static final String FIXTURE_PASSWORD = "FixtureOnly-7294-safe";

    @BeforeEach void fixtures() {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        String id = UUID.randomUUID().toString().substring(0, 8);
        teamA = teams.save(Team.builder().slug("objz-a-" + id).name("ObjZ A").build());
        teamB = teams.save(Team.builder().slug("objz-b-" + id).name("ObjZ B").build());
        agentA = save("agentA-" + id, Role.USER, teamA);
        agentB = save("agentB-" + id, Role.USER, teamB);
        adminB = save("adminB-" + id, Role.ADMIN, teamB);
        deptA = departments.save(Department.builder().team(teamA).name("DeptA-" + id).build());
        subA = subcategories.save(Subcategory.builder().team(teamA).department(deptA)
                .name("SubA-" + id).active(true).build());
        deptB = departments.save(Department.builder().team(teamB).name("DeptB-" + id).build());
        subB = subcategories.save(Subcategory.builder().team(teamB).department(deptB)
                .name("SubB-" + id).active(true).build());
        ticketOfB = tickets.save(Ticket.builder().team(teamB).title("B ticket")
                .content("b-content").status(TicketStatus.IN_PROGRESS)
                .department(deptB).subcategory(subB)
                .submittedBy(agentB).submittedAt(java.time.Instant.now()).build());
    }
    @AfterEach void clear() {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
    User save(String name, Role role, Team owner) {
        return users.save(User.builder().username(name).passwordHash(encoder.encode(FIXTURE_PASSWORD))
                .role(role).team(owner).active(true).build());
    }
    String bearer(User u) {
        return "Bearer " + jwt.generateToken(u.getUsername(), u.getRole().name(), u.getId(),
                u.getTeam() == null ? null : u.getTeam().getId(), u.getTokenVersion());
    }

    // --- Cross-team ticket IDOR (deny responses are 404 on purpose: the system
    // hides object existence instead of confirming it with 403) --------------------

    @Test void userCannotReadAnotherTeamsTicket() throws Exception {
        mvc.perform(get("/api/tickets/" + ticketOfB.getId()).header("Authorization", bearer(agentA)))
                .andExpect(status().isNotFound());
    }

    @Test void userCannotDeleteAnotherTeamsTicket() throws Exception {
        mvc.perform(delete("/api/user/tickets/" + ticketOfB.getId()).header("Authorization", bearer(agentA)))
                .andExpect(status().isNotFound());
        assertThat(tickets.findById(ticketOfB.getId())).isPresent();
    }

    @Test void ownerCanDeleteOwnTicket() throws Exception {
        mvc.perform(delete("/api/user/tickets/" + ticketOfB.getId()).header("Authorization", bearer(agentB)))
                .andExpect(status().isNoContent());
        assertThat(tickets.findById(ticketOfB.getId())).isEmpty();
    }

    @Test void sameTeamAdminCanReadMembersTicket() throws Exception {
        mvc.perform(get("/api/tickets/" + ticketOfB.getId()).header("Authorization", bearer(adminB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ticketOfB.getId().intValue()));
    }

    @Test void foreignAdminTicketStatusChangeIsRejected() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        User adminA = save("adminA-" + id, Role.ADMIN, teamA);
        mvc.perform(patch("/api/admin/tickets/" + ticketOfB.getId() + "/status")
                        .header("Authorization", bearer(adminA))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isNotFound());
    }

    // --- Route-level RBAC ----------------------------------------------------------

    @Test void userCannotListAdminTickets() throws Exception {
        mvc.perform(get("/api/admin/tickets").header("Authorization", bearer(agentA)))
                .andExpect(status().isForbidden());
    }

    @Test void adminListsOnlyOwnTeamTickets() throws Exception {
        mvc.perform(get("/api/admin/tickets").header("Authorization", bearer(adminB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(ticketOfB.getId().intValue()));
    }

    @Test void userCannotModifyDepartments() throws Exception {
        // A USER hitting admin-only write surfaces → route RBAC 403 regardless of payload.
        mvc.perform(post("/api/admin/projects")
                        .header("Authorization", bearer(agentA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/projects")
                        .header("Authorization", bearer(agentA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\",\"teamId\":\"" + teamA.getId() + "\"}"))
                .andExpect(status().isForbidden());
    }
}
