package com.dataentry.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

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

    public record TeamRef(
            Long id,
            String slug,
            String name,
            String nameEn,
            String nameAr,
            String color
    ) {}

    public record UserDto(
            Long id,
            String username,
            String displayName,
            String role,
            Instant avatarUpdatedAt,
            TeamRef team,
            boolean impersonating
    ) {}
}
