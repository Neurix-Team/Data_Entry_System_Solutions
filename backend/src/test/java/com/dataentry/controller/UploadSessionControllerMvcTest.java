package com.dataentry.controller;

import com.dataentry.dto.UploadSessionDtos;
import com.dataentry.model.Role;
import com.dataentry.model.User;
import com.dataentry.service.ChunkedUploadService;
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

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer contract for /api/uploads/sessions, which the browser's chunked uploader
 * depends on: JSON session creation with validation, and a raw-body chunk PUT whose bytes
 * reach the service untouched (no multipart framing, any Content-Type).
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class UploadSessionControllerMvcTest {

    private static final String SESSION_ID = "11111111-2222-3333-4444-555555555555";

    @Autowired MockMvc mvc;
    @MockBean ChunkedUploadService service;

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
    void createSession_returnsChunkLayout() throws Exception {
        Mockito.when(service.create(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new UploadSessionDtos.SessionResponse(
                        SESSION_ID, "book.pdf", 100L, 8, 13, List.of(), Instant.now()));

        mvc.perform(post("/api/uploads/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"book.pdf\",\"size\":100,\"target\":\"TICKET_DOCUMENT\",\"ticketId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SESSION_ID))
                .andExpect(jsonPath("$.chunkBytes").value(8))
                .andExpect(jsonPath("$.totalChunks").value(13));
    }

    @Test
    void createSession_rejectsEmptyFile() throws Exception {
        mvc.perform(post("/api/uploads/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"book.pdf\",\"size\":0,\"target\":\"TICKET_DOCUMENT\",\"ticketId\":42}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chunk_streamsRawBodyToService() throws Exception {
        byte[] body = "hello chunk".getBytes();
        Mockito.when(service.writeChunk(
                        ArgumentMatchers.any(), ArgumentMatchers.eq(SESSION_ID),
                        ArgumentMatchers.eq(3), ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    InputStream in = inv.getArgument(3);
                    byte[] got = in.readAllBytes();
                    assertArrayEquals(body, got);
                    return new UploadSessionDtos.ChunkAck(3, got.length);
                });

        mvc.perform(put("/api/uploads/sessions/{id}/chunks/3", SESSION_ID)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.index").value(3))
                .andExpect(jsonPath("$.bytes").value(body.length));
    }
}
