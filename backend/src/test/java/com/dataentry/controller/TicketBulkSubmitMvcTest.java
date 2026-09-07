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
