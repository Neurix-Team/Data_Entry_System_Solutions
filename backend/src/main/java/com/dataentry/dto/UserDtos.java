package com.dataentry.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public class UserDtos {

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 100)
            @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "Username can only contain letters, numbers, dot, underscore, and dash")
            String username,

            @NotBlank @Size(min = 6, max = 200)
            String password,

            @Size(max = 150)
            String displayName,

            @Email @Size(max = 200)
            String email,

            @Size(max = 40)
            String phone,

            @NotBlank
            @Pattern(regexp = "ADMIN|USER", message = "Role must be ADMIN or USER")
            String role
    ) {}

    public record UpdateUserRequest(
            @Size(max = 150) String displayName,
            @Email @Size(max = 200) String email,
            @Size(max = 40) String phone,
            @Size(min = 6, max = 200) String password,
            Boolean active
    ) {}

    public record UserResponse(
            Long id,
            String username,
            String displayName,
            String email,
            String phone,
            String role,
            boolean active,
            Instant createdAt
    ) {}
}
