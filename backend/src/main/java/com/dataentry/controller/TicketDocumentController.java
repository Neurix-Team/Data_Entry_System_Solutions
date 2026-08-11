package com.dataentry.controller;

import com.dataentry.dto.TicketDtos;
import com.dataentry.model.Role;
import com.dataentry.model.User;
import com.dataentry.service.TicketDocumentService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Attachments: upload/download/delete files hanging off a specific ticket. */
@RestController
@RequestMapping("/api/tickets/{ticketId}/documents")
public class TicketDocumentController {

    private final TicketDocumentService service;

    public TicketDocumentController(TicketDocumentService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TicketDtos.DocumentResponse upload(
            @PathVariable Long ticketId,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "name", required = false) String name,
            @AuthenticationPrincipal User current) {
        return service.upload(ticketId, name, file, current, current.getRole() == Role.ADMIN);
    }

    @GetMapping("/{docId}")
    public ResponseEntity<Resource> download(
            @PathVariable Long ticketId,
            @PathVariable Long docId,
            @AuthenticationPrincipal User current) {
        TicketDocumentService.DownloadHandle h = service.download(
                ticketId, docId, current, current.getRole() == Role.ADMIN);
        String encoded = URLEncoder.encode(h.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        MediaType type;
        try {
            type = h.contentType() != null ? MediaType.parseMediaType(h.contentType()) : MediaType.APPLICATION_OCTET_STREAM;
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(h.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + h.filename().replace("\"", "") + "\"; filename*=UTF-8''" + encoded)
                .body(h.resource());
    }

    @DeleteMapping("/{docId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long ticketId,
            @PathVariable Long docId,
            @AuthenticationPrincipal User current) {
        service.delete(ticketId, docId, current, current.getRole() == Role.ADMIN);
        return ResponseEntity.noContent().build();
    }
}
