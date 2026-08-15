package com.dataentry.controller;

import com.dataentry.dto.TicketDtos;
import com.dataentry.model.Role;
import com.dataentry.model.User;
import com.dataentry.service.TicketDocumentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer test for POST /api/tickets/{id}/documents. Guards the multipart contract that
 * the frontend upload flow depends on.
 *
 * <p>The bug that motivated this test: axios was defaulting Content-Type to application/json,
 * which meant every attempted document upload landed on the multipart endpoint as JSON and
 * came back as an opaque 500. We now expect a clean 415 for that misuse — asserted below.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class TicketDocumentUploadMvcTest {

    @Autowired MockMvc mvc;
    @MockBean TicketDocumentService documentService;

    @BeforeEach
    void authenticate() {
        // Filters are disabled to keep the test focused on MVC routing, so we hand-fill the
        // SecurityContext with the actual User principal that @AuthenticationPrincipal reads.
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
    void multipartUpload_returns200() throws Exception {
        Mockito.when(documentService.upload(
                        ArgumentMatchers.eq(42L), ArgumentMatchers.any(),
                        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyBoolean()))
                .thenReturn(new TicketDtos.DocumentResponse(
                        1L, "report.pdf", "report.pdf", "application/pdf", 12L, Instant.now()));

        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "hello world!".getBytes());
        MockMultipartFile name = new MockMultipartFile(
                "name", "", "text/plain", "Report".getBytes());

        mvc.perform(multipart("/api/tickets/42/documents").file(file).file(name))
                .andExpect(status().isOk());
    }

    /**
     * Regression guard for the axios FormData bug. If the client accidentally sends
     * {@code Content-Type: application/json} to the multipart endpoint (as our axios instance
     * did before the fix), Spring MVC MUST route it to 415 Unsupported Media Type — not fall
     * through to the generic 500 handler and confuse the user with "Unexpected server error".
     */
    @Test
    void jsonUpload_returns415() throws Exception {
        mvc.perform(post("/api/tickets/42/documents")
                        .contentType("application/json")
                        .content("{\"file\":\"nope\"}"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
