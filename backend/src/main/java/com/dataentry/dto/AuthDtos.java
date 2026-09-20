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

    /**
     * Result of POST /api/auth/login.
     *
     * <p>Either a session exists ({@code mfaRequired == false}, {@code token} populated) or the
     * password was right and the second factor is still owed ({@code mfaRequired == true},
     * {@code token == null}, {@code mfaTicket} populated). The challenge branch deliberately
     * carries no user object and no session cookie.</p>
     */
    public record LoginResponse(
            String token,
            long expiresInMs,
            UserDto user,
            boolean mfaRequired,
            String mfaTicket,
            int mfaPeriodSeconds
    ) {
        /** Ordinary fully-authenticated login. */
        public static LoginResponse authenticated(String token, long expiresInMs, UserDto user) {
            return new LoginResponse(token, expiresInMs, user, false, null, 0);
        }

        /** Credentials were right, but the factor is still owed: no token is issued yet. */
        public static LoginResponse challenge(String mfaTicket, int periodSeconds) {
            return new LoginResponse(null, 0L, null, true, mfaTicket, periodSeconds);
        }
    }

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
