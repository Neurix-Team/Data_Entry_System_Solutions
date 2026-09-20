package com.dataentry.controller;

import com.dataentry.dto.AuthDtos;
import com.dataentry.model.User;
import com.dataentry.security.JwtAuthFilter;
import com.dataentry.security.TenantContext;
import com.dataentry.service.AuthService;
import com.dataentry.service.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final LoginRateLimiter rateLimiter;
    private final boolean cookieSecure;
    private final com.dataentry.security.ClientAddressResolver addresses;

    /** Refresh exchanges are throttled per account and per network. */
    private static final String PASSWORD_CHANGE_KEY_PREFIX = "pwchange:";

    @org.springframework.beans.factory.annotation.Autowired
    public AuthController(AuthService authService,
                          LoginRateLimiter rateLimiter,
                          @Value("${app.auth.cookie-secure:true}") boolean cookieSecure,
                          com.dataentry.security.ClientAddressResolver addresses) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.cookieSecure = cookieSecure;
        this.addresses = addresses;
    }

    public AuthController(AuthService authService, LoginRateLimiter rateLimiter, boolean cookieSecure) {
        this(authService, rateLimiter, cookieSecure, new com.dataentry.security.ClientAddressResolver(""));
    }

    @GetMapping("/csrf")
    public java.util.Map<String, String> csrf(org.springframework.security.web.csrf.CsrfToken token) {
        return java.util.Map.of("token", token.getToken());
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.LoginResponse> login(@Valid @RequestBody AuthDtos.LoginRequest req,
                                                        HttpServletRequest http) {
        String key = "account:" + req.username().trim().toLowerCase(java.util.Locale.ROOT);
        String networkKey = "network:" + addresses.resolve(http);
        if (!rateLimiter.tryAcquire(networkKey) || !rateLimiter.tryAcquire(key)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many login attempts. Try again later.");
        }
        AuthDtos.LoginResponse resp = authService.login(req);
        rateLimiter.reset(key);
        ResponseCookie cookie = buildAuthCookie(resp.token(), Duration.ofMillis(resp.expiresInMs()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(resp);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthDtos.LoginResponse> refresh(
            jakarta.servlet.http.HttpServletRequest http) {
        // Resolve the presented token exactly like JwtAuthFilter: Bearer header first,
        // then the HttpOnly cookie. Refresh must work on a cold tab (cookie only).
        String token = null;
        String auth = http.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            token = auth.substring(7);
        } else if (http.getCookies() != null) {
            for (jakarta.servlet.http.Cookie c : http.getCookies()) {
                if (JwtAuthFilter.AUTH_COOKIE.equals(c.getName())
                        && c.getValue() != null && !c.getValue().isBlank()) {
                    token = c.getValue();
                    break;
                }
            }
        }
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No session to refresh.");
        }
        // Throttle per presented-token identity so an attacker cannot ride an old token
        // to mint unlimited fresh ones; network key stops blind hammering.
        String accountKey = "refresh:account:" + token.hashCode();
        String networkKey = "refresh:network:" + addresses.resolve(http);
        if (!rateLimiter.tryAcquire(networkKey) || !rateLimiter.tryAcquire(accountKey)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many refresh attempts. Try again later.");
        }
        AuthDtos.LoginResponse resp;
        try {
            resp = authService.refresh(token);
        } catch (ResponseStatusException e) {
            rateLimiter.reset(accountKey);
            throw e;
        }
        rateLimiter.reset(accountKey);
        ResponseCookie cookie = buildAuthCookie(resp.token(), Duration.ofMillis(resp.expiresInMs()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(resp);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        ResponseCookie clear = buildAuthCookie("", Duration.ZERO);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clear.toString())
                .build();
    }

    @PostMapping("/logout-everywhere")
    public ResponseEntity<AuthDtos.LoginResponse> logoutEverywhere(@AuthenticationPrincipal User user) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in.");
        }
        AuthDtos.LoginResponse resp = authService.logoutEverywhere(user);
        ResponseCookie cookie = buildAuthCookie(resp.token(), Duration.ofMillis(resp.expiresInMs()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(resp);
    }

    @GetMapping("/me")
    public ResponseEntity<AuthDtos.UserDto> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(AuthService.toDto(user, TenantContext.isImpersonating()));
    }

    @PatchMapping("/me")
    public ResponseEntity<AuthDtos.UserDto> updateMe(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody AuthDtos.UpdateProfileRequest req) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in.");
        }
        return ResponseEntity.ok(
                authService.updateProfile(user, req, TenantContext.isImpersonating()));
    }

    @PostMapping("/me/password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody AuthDtos.ChangePasswordRequest req,
            HttpServletRequest http) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in.");
        }
        // Throttle current-password guessing by an authenticated session.
        String key = PASSWORD_CHANGE_KEY_PREFIX + user.getId();
        String networkKey = "pwchange:network:" + addresses.resolve(http);
        if (!rateLimiter.tryAcquire(networkKey) || !rateLimiter.tryAcquire(key)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many password changes. Try again later.");
        }
        authService.changePassword(user, req);
        rateLimiter.reset(key);
        return ResponseEntity.noContent().build();
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
