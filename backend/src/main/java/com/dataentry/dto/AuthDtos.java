package com.dataentry.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public class AuthDtos {

    public record LoginRequest(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(max = 200) String password
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
            String email,
            String phone,
            Instant avatarUpdatedAt,
            Instant createdAt,
            TeamRef team,
            boolean impersonating
    ) {}

    public record UpdateProfileRequest(
            @Size(max = 150) String displayName,
            @Email @Size(max = 200) String email,
            @Size(max = 40) String phone
    ) {}

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 200) String newPassword
    ) {}
}
