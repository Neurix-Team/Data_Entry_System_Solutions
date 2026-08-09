package com.dataentry.controller;

import com.dataentry.dto.AiCheckDtos;
import com.dataentry.service.AiCheckService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AiCheckController {

    private final AiCheckService service;

    public AiCheckController(AiCheckService service) {
        this.service = service;
    }

    @PostMapping("/check")
    public ResponseEntity<AiCheckDtos.CheckResponse> check(@Valid @RequestBody AiCheckDtos.CheckRequest req) {
        return ResponseEntity.ok(service.check(req.content()));
    }
}
