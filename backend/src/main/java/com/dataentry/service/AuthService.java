package com.dataentry.service;

import com.dataentry.dto.AuthDtos;
import com.dataentry.model.Role;
import com.dataentry.model.Team;
import com.dataentry.model.User;
import com.dataentry.repository.UserRepository;
import com.dataentry.security.JwtAuthFilter;
import com.dataentry.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TranslationService translator;
    private final JwtAuthFilter jwtAuthFilter;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       TranslationService translator,
                       JwtAuthFilter jwtAuthFilter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.translator = translator;
        this.jwtAuthFilter = jwtAuthFilter;
    }

    public AuthDtos.LoginResponse login(AuthDtos.LoginRequest req) {
        User user = userRepository.findByUsername(req.username())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account disabled");
        }

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        // Non-super users without a team are a data-consistency bug (either seed forgot to
        // set one, or a migration left a row orphaned). Refuse rather than silently issuing
        // an unfilterable token.
        if (user.getRole() != Role.SUPER_ADMIN && user.getTeam() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Account is not attached to a team. Contact your administrator.");
        }

        Long teamId = user.getTeam() != null ? user.getTeam().getId() : null;
        String token = jwtService.generateToken(user.getUsername(), user.getRole().name(),
                user.getId(), teamId);

        return new AuthDtos.LoginResponse(token, jwtService.getExpirationMs(), toDto(user, false));
    }

    /** Build the {@code /auth/me} payload — used both after login and on session probe. */
    public static AuthDtos.UserDto toDto(User user, boolean impersonating) {
        return new AuthDtos.UserDto(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getRole().name(),
                user.getEmail(), user.getPhone(),
                user.getAvatarUpdatedAt(),
                user.getCreatedAt(),
                teamRef(user.getTeam()),
                impersonating
        );
    }

    public static AuthDtos.TeamRef teamRef(Team t) {
        if (t == null) return null;
        return new AuthDtos.TeamRef(t.getId(), t.getSlug(), t.getName(),
                t.getNameEn(), t.getNameAr(), t.getColor());
    }

    /**
     * Self-service profile update. Only the currently authenticated user can edit their own
     * displayName/email/phone — nothing else (role/team/active stays owned by an admin). If
     * the display name changes, the translated variants are re-generated so bilingual UIs
     * stay consistent.
     */
    @Transactional
    public AuthDtos.UserDto updateProfile(User caller, AuthDtos.UpdateProfileRequest req,
                                          boolean impersonating) {
        User user = userRepository.findById(caller.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Session user no longer exists."));
        boolean nameChanged = false;
        if (req.displayName() != null) {
            String trimmed = req.displayName().trim();
            if (trimmed.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Display name cannot be blank.");
            }
            if (!trimmed.equals(user.getDisplayName())) {
                user.setDisplayName(trimmed);
                nameChanged = true;
            }
        }
        if (req.email() != null) {
            user.setEmail(req.email().isBlank() ? null : req.email().trim());
        }
        if (req.phone() != null) {
            user.setPhone(req.phone().isBlank() ? null : req.phone().trim());
        }
        if (nameChanged) {
            try {
                TranslationService.Bilingual bi = translator.toBoth(user.getDisplayName());
                user.setDisplayNameEn(bi.en());
                user.setDisplayNameAr(bi.ar());
            } catch (Exception ignored) {
                // Translation is best-effort — keep the raw display name in both fields
                // rather than blocking a profile save on a translation-service outage.
                user.setDisplayNameEn(user.getDisplayName());
                user.setDisplayNameAr(user.getDisplayName());
            }
        }
        User saved = userRepository.save(user);
        // The JWT auth cache still holds the pre-edit User entity, which the /me endpoint
        // reads on the next request. Evict so the next call sees the fresh copy.
        jwtAuthFilter.evictUser(saved.getId());
        return toDto(saved, impersonating);
    }

    /**
     * Self-service password change. Requires the old password so a stolen cookie can't
     * silently rotate the credential. Rejects a no-op (new == old) to avoid a confused
     * user thinking they "changed" the password when they didn't.
     */
    @Transactional
    public void changePassword(User caller, AuthDtos.ChangePasswordRequest req) {
        User user = userRepository.findById(caller.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Session user no longer exists."));
        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Current password is incorrect.");
        }
        if (passwordEncoder.matches(req.newPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The new password must be different from the current one.");
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        userRepository.save(user);
        // Bounce every cached session for this user — the credential just changed, so no
        // pooled principal should keep authorising requests.
        jwtAuthFilter.evictUser(user.getId());
    }
}
