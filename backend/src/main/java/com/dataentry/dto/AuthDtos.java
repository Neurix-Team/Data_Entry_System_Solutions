package com.dataentry.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
     * Everything the frontend needs to render the current session (topbar chip, profile
     * page). Email and phone are included so the profile page can populate its form
     * without a separate round-trip.
     */
    public record UserDto(
            Long id,
            String username,
            String displayName,
            String role,
            String email,
            String phone,
            Instant avatarUpdatedAt,
            Instant createdAt,
            TeamRef team,
            boolean impersonating
    ) {}

    /** PATCH /api/auth/me — free-form fields the user can change about themselves. */
    public record UpdateProfileRequest(
            @Size(max = 150) String displayName,
            @Email @Size(max = 200) String email,
            @Size(max = 40) String phone
    ) {}

    /**
     * POST /api/auth/me/password. currentPassword is required to prove ownership so a stolen
     * cookie/session can't rotate the password without knowing the previous one.
     */
    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 200) String newPassword
    ) {}
}
