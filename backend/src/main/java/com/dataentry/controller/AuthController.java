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

    public AuthController(AuthService authService,
                          LoginRateLimiter rateLimiter,
                          @Value("${app.auth.cookie-secure:true}") boolean cookieSecure) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.cookieSecure = cookieSecure;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.LoginResponse> login(@Valid @RequestBody AuthDtos.LoginRequest req,
                                                        HttpServletRequest http) {
        String key = clientKey(http, req.username());
        if (!rateLimiter.tryAcquire(key)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many login attempts. Try again later.");
        }
        AuthDtos.LoginResponse resp = authService.login(req);
        rateLimiter.reset(key);
        // Set an httpOnly cookie so the browser doesn't need to touch the token from JS —
        // shields against XSS-driven token theft. We still return the token in the body for
        // non-browser callers (CLI, mobile apps) that use the Authorization header.
        ResponseCookie cookie = buildAuthCookie(resp.token(), Duration.ofMillis(resp.expiresInMs()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(resp);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        // Clear the cookie by setting Max-Age=0 with the same attributes so browsers accept it.
        ResponseCookie clear = buildAuthCookie("", Duration.ZERO);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clear.toString())
                .build();
    }

    @GetMapping("/me")
    public ResponseEntity<AuthDtos.UserDto> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(AuthService.toDto(user, TenantContext.isImpersonating()));
    }

    /**
     * Self-service profile update — display name / email / phone. Anything more
     * consequential (role, team, active) still requires an admin flow.
     */
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
            @Valid @RequestBody AuthDtos.ChangePasswordRequest req) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in.");
        }
        authService.changePassword(user, req);
        return ResponseEntity.noContent().build();
    }

    private ResponseCookie buildAuthCookie(String value, Duration maxAge) {
        return ResponseCookie.from(JwtAuthFilter.AUTH_COOKIE, value)
                .httpOnly(true)             // JS cannot read this cookie — XSS can't steal it
                .secure(cookieSecure)       // only sent over HTTPS in production
                .sameSite("Lax")            // blocks cross-site POST CSRF while allowing top-level nav
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    /**
     * IP + lowercased username. Only trust X-Forwarded-For when the direct peer is on a
     * private subnet — a public client that ships the header itself would otherwise be able
     * to rotate its perceived IP and bypass the login rate limiter.
     */
    private String clientKey(HttpServletRequest http, String username) {
        String peer = http.getRemoteAddr();
        String ip = peer;
        if (isTrustedProxy(peer)) {
            String xff = http.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                ip = (comma > 0 ? xff.substring(0, comma) : xff).trim();
            }
        }
        String u = username == null ? "" : username.trim().toLowerCase();
        return ip + ":" + u;
    }

    private static boolean isTrustedProxy(String addr) {
        if (addr == null) return false;
        // Private IPv4 ranges + loopback + IPv6 loopback + ULA. Requests coming directly
        // from the public internet will not match, so their XFF is ignored.
        return addr.startsWith("10.")
                || addr.startsWith("192.168.")
                || addr.startsWith("127.")
                || addr.equals("::1")
                || addr.startsWith("fd") || addr.startsWith("fc")
                || matchesRange172(addr);
    }

    private static boolean matchesRange172(String addr) {
        if (!addr.startsWith("172.")) return false;
        int firstDot = addr.indexOf('.', 4);
        if (firstDot < 0) return false;
        try {
            int octet = Integer.parseInt(addr.substring(4, firstDot));
            return octet >= 16 && octet <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
