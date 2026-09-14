package com.dataentry.controller;

import com.dataentry.dto.AssignmentDtos;
import com.dataentry.model.Role;
import com.dataentry.model.User;
import com.dataentry.service.AssignmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class AssignmentControllerMvcTest {

    @Autowired MockMvc mvc;
    @MockBean AssignmentService service;

    private final User caller = User.builder().id(7L).username("lead").role(Role.ADMIN).active(true).build();

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller, null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                                new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static AssignmentDtos.Response sample(String status) {
        AssignmentDtos.Person agent = new AssignmentDtos.Person(20L, "agent", "Agent One", null, null, null);
        AssignmentDtos.Person lead = new AssignmentDtos.Person(7L, "lead", "Lead", null, null, null);
        return new AssignmentDtos.Response(5L, "Scan box 4", null, status, LocalDate.of(2026, 9, 20),
                agent, lead, Instant.parse("2026-09-14T08:00:00Z"),
                "DONE".equals(status) ? Instant.parse("2026-09-14T09:00:00Z") : null);
    }

    @Test
    void create_rejectsBlankTitle() throws Exception {
        mvc.perform(post("/api/admin/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": 20, \"title\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.title").exists());
        Mockito.verify(service, Mockito.never()).create(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void create_rejectsMissingAssignee() throws Exception {
        mvc.perform(post("/api/admin/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Scan box 4\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.assigneeId").exists());
    }

    @Test
    void create_passesTheCallerAndReturnsTheRow() throws Exception {
        Mockito.when(service.create(eq(caller), ArgumentMatchers.any())).thenReturn(sample("OPEN"));

        mvc.perform(post("/api/admin/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": 20, \"title\": \"Scan box 4\", \"dueDate\": \"2026-09-20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.assignee.username").value("agent"))
                .andExpect(jsonPath("$.dueDate").value("2026-09-20"));
    }

    @Test
    void markDone_routesToTheCaller() throws Exception {
        Mockito.when(service.markDone(5L, caller)).thenReturn(sample("DONE"));

        mvc.perform(post("/api/user/assignments/5/done"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.completedAt").exists());
    }
}
