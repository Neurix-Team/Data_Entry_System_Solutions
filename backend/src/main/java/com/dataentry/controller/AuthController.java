package com.dataentry.controller;

import com.dataentry.dto.AuthDtos;
import com.dataentry.model.User;
import com.dataentry.security.JwtAuthFilter;
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
        return ResponseEntity.ok(new AuthDtos.UserDto(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getRole().name(),
                user.getAvatarUpdatedAt()
        ));
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

    /** IP + lowercased username. Trust X-Forwarded-For only if the deployment sets it via a
     *  trusted reverse proxy — behind our own Nginx that's fine. */
    private String clientKey(HttpServletRequest http, String username) {
        String ip = http.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            int comma = ip.indexOf(',');
            ip = (comma > 0 ? ip.substring(0, comma) : ip).trim();
        } else {
            ip = http.getRemoteAddr();
        }
        String u = username == null ? "" : username.trim().toLowerCase();
        return ip + ":" + u;
    }
}
