package com.dataentry.service;

import com.dataentry.dto.AuthDtos;
import com.dataentry.model.Role;
import com.dataentry.model.Team;
import com.dataentry.model.User;
import com.dataentry.repository.UserRepository;
import com.dataentry.security.JwtService;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class AuthService {

    /** Expired tokens within this window may be exchanged at /api/auth/refresh. */
    static final Duration REFRESH_GRACE = Duration.ofDays(7);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TranslationService translator;
    private final PasswordPolicy passwordPolicy;
    private final MfaService mfaService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       TranslationService translator,
                       PasswordPolicy passwordPolicy,
                       MfaService mfaService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.translator = translator;
        this.passwordPolicy = passwordPolicy;
        this.mfaService = mfaService;
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

        return issueSessionOrChallenge(user);
    }


    /** Issues a session, or — when MFA is in force for this account — a challenge ticket. */
    AuthDtos.LoginResponse issueSessionOrChallenge(User user) {
        if (mfaService.isLocked(user)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    mfaService.lockoutMessage(user));
        }
        boolean enrolled = mfaService.isEnrolled(user);
        // An enrolled account owes a code; a privileged account with no device yet owes
        // first-run enrollment — both continue on the short-lived pending ticket.
        if (enrolled || mfaService.isMandatory(user.getRole())) {
            return AuthDtos.LoginResponse.challenge(
                    mfaService.issueChallengeTicket(user), mfaService.periodSeconds());
        }
        return issueSession(user);
    }

    AuthDtos.LoginResponse issueSession(User user) {
        Long teamId = user.getTeam() != null ? user.getTeam().getId() : null;
        String token = jwtService.generateToken(user.getUsername(), user.getRole().name(),
                user.getId(), teamId, user.getTokenVersion());
        return AuthDtos.LoginResponse.authenticated(
                token, jwtService.getExpirationMs(), toDto(user, false));
    }

    /**
     * Completes a sign-in challenge: validates the one-time ticket, checks the code against
     * the account's device (or a recovery code) and issues the real session. The ticket's
     * tokenVersion is deliberately not enforced here — it is minutes-old and single-purpose,
     * and first-run enrollment bumps the version between ticket issue and verification.
     * Not transactional on purpose: verifyChallenge commits its own work, so the attempt
     * counter survives the 401/429 this method may throw afterwards.
     */
    public AuthDtos.LoginResponse completeMfa(String ticket, String code) {
        Claims claims;
        try {
            claims = jwtService.parse(ticket);
        } catch (io.jsonwebtoken.JwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "This challenge has expired. Sign in again.");
        }
        if (!Boolean.TRUE.equals(claims.get(JwtService.MFA_PENDING_CLAIM, Boolean.class))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Not an MFA challenge ticket.");
        }
        String username = claims.getSubject();
        Object uid = claims.get("uid");
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid challenge ticket.");
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Invalid challenge ticket."));
        boolean identityMatches = uid instanceof Number n
                && user.getId() != null && n.longValue() == user.getId();
        if (!identityMatches || !user.isActive()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid challenge ticket.");
        }
        if (user.getRole() != Role.SUPER_ADMIN
                && (user.getTeam() == null || !user.getTeam().isActive())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Account team is unavailable. Contact your administrator.");
        }
        MfaService.ChallengeResult result = mfaService.verifyChallenge(user, code);
        return switch (result) {
            case OK, RECOVERED -> issueSession(user);
            case LOCKED -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    mfaService.lockoutMessage(user));
            case NOT_ENROLLED -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Two-factor authentication is no longer set up on this account. "
                            + "Sign in again.");
            case INVALID -> throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "That code is not valid. Check your device clock and try the next code.");
        };
    }

    @Transactional
    public AuthDtos.LoginResponse logoutEverywhere(User caller) {
        User user = userRepository.findById(caller.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Session user no longer exists."));
        user.setTokenVersion(user.getTokenVersion() + 1);
        User saved = userRepository.save(user);
        return issueSession(saved);
    }

    /**
     * Exchanges a valid or recently-expired token (within {@link #REFRESH_GRACE}) for a
     * fresh one. Identity, account state, team state and the revocation version
     * (tokenVersion) are fully re-validated — a token that was revoked by
     * logout-everywhere or a password change is rejected even inside the grace window,
     * which is what keeps a stolen token's blast radius bounded.
     */
    @Transactional
    public AuthDtos.LoginResponse refresh(String oldToken) {
        Claims claims;
        try {
            claims = jwtService.parseLenient(oldToken);
        } catch (io.jsonwebtoken.JwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token.");
        }
        String username = claims.getSubject();
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token.");
        }
        // A pending ticket proves only the password; it must never be upgradable to a
        // session by way of refresh — the second factor has to be answered first.
        if (Boolean.TRUE.equals(claims.get(JwtService.MFA_PENDING_CLAIM, Boolean.class))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Complete two-factor verification before refreshing.");
        }
        Date expiry = claims.getExpiration();
        if (expiry == null
                || expiry.toInstant().isBefore(Instant.now().minus(REFRESH_GRACE))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired. Sign in again.");
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token."));
        Object uid = claims.get("uid");
        boolean identityMatches = uid instanceof Number n
                && user.getId() != null && n.longValue() == user.getId();
        if (!identityMatches || !user.isActive()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token.");
        }
        if (user.getRole() != Role.SUPER_ADMIN
                && (user.getTeam() == null || !user.getTeam().isActive())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Account team is unavailable. Contact your administrator.");
        }
        if (claims.get("tv") instanceof Number tv && tv.longValue() != user.getTokenVersion()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session revoked. Sign in again.");
        }

        return issueSession(user);
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
