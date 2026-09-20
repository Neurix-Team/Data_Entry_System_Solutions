package com.dataentry.controller;

import com.dataentry.dto.AuthDtos;
import com.dataentry.dto.MfaDtos;
import com.dataentry.model.User;
import com.dataentry.security.JwtAuthFilter;
import com.dataentry.service.AuthService;
import com.dataentry.service.MfaService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

/**
 * Second-factor (TOTP) endpoints. Every route here lives behind the same JWT machinery as
 * the rest of the API; {@link JwtAuthFilter} decides which of them a half-authenticated
 * (mfa_pending) ticket may touch.
 */
@RestController
@RequestMapping("/api/auth/mfa")
public class MfaController {

    private final AuthService authService;
    private final MfaService mfaService;
    private final PasswordEncoder passwordEncoder;
    private final boolean cookieSecure;

    public MfaController(AuthService authService,
                         MfaService mfaService,
                         PasswordEncoder passwordEncoder,
                         @Value("${app.auth.cookie-secure:true}") boolean cookieSecure) {
        this.authService = authService;
        this.mfaService = mfaService;
        this.passwordEncoder = passwordEncoder;
        this.cookieSecure = cookieSecure;
    }

    /**
     * Completes a sign-in challenge. Open to anonymous callers on purpose: the one-time
     * ticket in the body is the credential, and the durable per-account attempt budget in
     * MfaService throttles code guessing.
     */
    @PostMapping("/verify")
    public ResponseEntity<AuthDtos.LoginResponse> verify(@Valid @RequestBody MfaDtos.VerifyRequest req) {
        AuthDtos.LoginResponse resp = authService.completeMfa(req.ticket(), req.code());
        ResponseCookie cookie = buildAuthCookie(resp.token(), Duration.ofMillis(resp.expiresInMs()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(resp);
    }

    /** MFA state of the caller; the shared secret never appears here. */
    @GetMapping("/status")
    public MfaDtos.StatusResponse status(@AuthenticationPrincipal User user) {
        requireUser(user);
        return mfaService.status(user);
    }

    /**
     * Starts enrollment. The password is demanded again on purpose: enrollment must never
     * ride on a session or ticket alone, or a hijacked browser could quietly attach the
     * attacker's device to the account.
     */
    @PostMapping("/enroll")
    public MfaDtos.EnrollResponse enroll(@AuthenticationPrincipal User user,
                                         @Valid @RequestBody MfaDtos.EnrollRequest req) {
        requireUser(user);
        assertPassword(user, req.password());
        return mfaService.beginEnrollment(user);
    }

    /** Confirms enrollment with the first code from the authenticator; issues recovery codes. */
    @PostMapping("/enroll/confirm")
    public MfaDtos.RecoveryCodesResponse confirmEnrollment(@AuthenticationPrincipal User user,
                                                            @Valid @RequestBody MfaDtos.CodeRequest req) {
        requireUser(user);
        return mfaService.confirmEnrollment(user, req.code());
    }

    /**
     * Self-service disable. Needs a currently valid code, so a stolen session alone cannot
     * strip the factor; refused outright while the role mandates MFA.
     */
    @PostMapping("/disable")
    public MfaDtos.StatusResponse disable(@AuthenticationPrincipal User user,
                                           @Valid @RequestBody MfaDtos.CodeRequest req) {
        requireUser(user);
        mfaService.disable(user, req.code());
        return mfaService.status(user);
    }

    /** Re-issues recovery codes (password-gated); the previous set stops working. */
    @PostMapping("/recovery-codes")
    public MfaDtos.RecoveryCodesResponse recoveryCodes(@AuthenticationPrincipal User user,
                                                        @Valid @RequestBody MfaDtos.EnrollRequest req) {
        requireUser(user);
        assertPassword(user, req.password());
        return mfaService.regenerateRecoveryCodes(user);
    }

    private void requireUser(User user) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in.");
        }
    }

    private void assertPassword(User user, String password) {
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "That password is not correct.");
        }
    }

    private ResponseCookie buildAuthCookie(String value, Duration maxAge) {
        return ResponseCookie.from(JwtAuthFilter.AUTH_COOKIE, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
