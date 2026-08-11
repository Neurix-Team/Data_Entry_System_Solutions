package com.dataentry.controller;

import com.dataentry.dto.ChatDtos.ChatRequest;
import com.dataentry.dto.ChatDtos.ChatResponse;
import com.dataentry.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService service;

    public ChatController(ChatService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest req, Authentication auth) {
        String role = "USER";
        if (auth != null) {
            for (GrantedAuthority ga : auth.getAuthorities()) {
                if ("ROLE_ADMIN".equals(ga.getAuthority())) { role = "ADMIN"; break; }
            }
        }
        return ResponseEntity.ok(service.reply(req.message(), role, req.lang(), req.currentPath()));
    }
}
