package com.dataentry.controller;

import com.dataentry.dto.AuthDtos;
import com.dataentry.model.User;
import com.dataentry.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.LoginResponse> login(@Valid @RequestBody AuthDtos.LoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
    }

    @GetMapping("/me")
    public ResponseEntity<AuthDtos.UserDto> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(new AuthDtos.UserDto(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getRole().name()
        ));
    }
}
