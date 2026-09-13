package com.dataentry.service;

import com.dataentry.dto.AuthDtos;
import com.dataentry.model.Role;
import com.dataentry.model.Team;
import com.dataentry.model.User;
import com.dataentry.repository.UserRepository;
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
    private final PasswordPolicy passwordPolicy;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       TranslationService translator,
                       PasswordPolicy passwordPolicy) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.translator = translator;
        this.passwordPolicy = passwordPolicy;
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

        if (user.getRole() != Role.SUPER_ADMIN
                && (user.getTeam() == null || !user.getTeam().isActive())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Account team is unavailable. Contact your administrator.");
        }

        Long teamId = user.getTeam() != null ? user.getTeam().getId() : null;
        String token = jwtService.generateToken(user.getUsername(), user.getRole().name(),
                user.getId(), teamId, user.getTokenVersion());

        return new AuthDtos.LoginResponse(token, jwtService.getExpirationMs(), toDto(user, false));
    }

    @Transactional
    public AuthDtos.LoginResponse logoutEverywhere(User caller) {
        User user = userRepository.findById(caller.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Session user no longer exists."));
        user.setTokenVersion(user.getTokenVersion() + 1);
        User saved = userRepository.save(user);
        Long teamId = saved.getTeam() != null ? saved.getTeam().getId() : null;
        String token = jwtService.generateToken(saved.getUsername(), saved.getRole().name(),
                saved.getId(), teamId, saved.getTokenVersion());
        return new AuthDtos.LoginResponse(token, jwtService.getExpirationMs(), toDto(saved, false));
    }

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
                user.setDisplayNameEn(user.getDisplayName());
                user.setDisplayNameAr(user.getDisplayName());
            }
        }
        User saved = userRepository.save(user);
        return toDto(saved, impersonating);
    }

    @Transactional
    public void changePassword(User caller, AuthDtos.ChangePasswordRequest req) {
        User user = userRepository.findById(caller.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Session user no longer exists."));
        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Current password is incorrect.");
        }
        var violations = passwordPolicy.validate(req.newPassword(), user.getUsername());
        if (!violations.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    PasswordPolicy.describe(violations.get(0)));
        }
        if (passwordEncoder.matches(req.newPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The new password must be different from the current one.");
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
    }
}
