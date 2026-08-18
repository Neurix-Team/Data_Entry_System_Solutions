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

    /**
     * The subset of user information the frontend needs. {@code team} is null for
     * SUPER_ADMIN accounts, present for every other role. {@code impersonating} is true
     * during a super-admin's "enter team" session — the UI uses it to render the red
     * "Exit impersonation" banner.
     */
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
