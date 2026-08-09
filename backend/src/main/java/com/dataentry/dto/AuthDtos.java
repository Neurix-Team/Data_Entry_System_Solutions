package com.dataentry.dto;

import jakarta.validation.constraints.NotBlank;

public class AuthDtos {

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {}

    public record LoginResponse(
            String token,
            long expiresInMs,
            UserDto user
    ) {}

    public record UserDto(
            Long id,
            String username,
            String displayName,
            String role
    ) {}
}
