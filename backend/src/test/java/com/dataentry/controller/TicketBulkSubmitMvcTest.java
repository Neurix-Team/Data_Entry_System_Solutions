package com.dataentry.controller;

import com.dataentry.dto.TicketDtos;
import com.dataentry.model.Role;
import com.dataentry.model.User;
import com.dataentry.service.TicketService;
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

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer test for POST /api/user/tickets/bulk with attachments-only articles.
 *
 * <p>Before the DTO validation relaxation, {@code ArticleRequest} carried {@code @NotBlank}
 * on both title and content — so users trying to submit a pure-attachment ticket got a 400
 * complaining about missing text fields they had intentionally left empty. This test locks
 * in the new behaviour: blank title/content is a valid payload.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class TicketBulkSubmitMvcTest {

    @Autowired MockMvc mvc;
    @MockBean TicketService ticketService;

    @BeforeEach
    void authenticate() {
        User caller = User.builder().id(7L).username("agent").role(Role.USER).active(true).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        caller, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bulkSubmit_acceptsBlankTitleAndContent() throws Exception {
        Mockito.when(ticketService.createMany(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new TicketDtos.BulkCreateResponse(1, List.of()));

        // Article with blank title AND blank content — the attachments-only case the frontend
        // now allows. The DTO relaxation should let this through validation.
        String payload = """
                {
                  "departmentId": 1,
                  "subcategoryId": 2,
                  "projectId": null,
                  "articles": [
                    { "title": "", "content": "", "resources": [], "extractedImages": [] }
                  ],
                  "customValues": {}
                }
                """;

        mvc.perform(post("/api/user/tickets/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));
    }

    @Test
    void bulkSubmit_stillRejectsEmptyArticleList() throws Exception {
        // Belt-and-braces: @NotEmpty on articles must still hold, otherwise a totally empty
        // request would return 200 with zero tickets and mask a client bug.
        String payload = """
                {
                  "departmentId": 1,
                  "subcategoryId": 2,
                  "articles": [],
                  "customValues": {}
                }
                """;

        mvc.perform(post("/api/user/tickets/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }
}
