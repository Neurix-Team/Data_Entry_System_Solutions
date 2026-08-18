package com.dataentry.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-ms}") long expirationMs) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is missing — set it via env (min 32 chars of high entropy).");
        }
        String lower = secret.toLowerCase();
        if (lower.startsWith("change-me")
                || lower.contains("please-rotate")
                || lower.contains("local-dev-secret")) {
            throw new IllegalStateException(
                    "JWT_SECRET is a known placeholder — generate a real random secret before booting " +
                    "(e.g. `openssl rand -base64 48`).");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least 32 bytes (got " + bytes.length + ").");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expirationMs = expirationMs;
    }

    /**
     * Standard login token — 24h lifetime, carries role and (for scoped roles) the team id.
     * SUPER_ADMIN tokens omit {@code tid} so any request they make bypasses the tenant filter
     * unless they opt in to impersonation via the {@code X-Impersonate-Team-Id} header.
     */
    public String generateToken(String username, String role, Long userId, Long teamId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        claims.put("uid", userId);
        // Omit tid entirely for cross-tenant roles so a stale token can't accidentally scope
        // a SUPER_ADMIN to a specific team.
        if (teamId != null) claims.put("tid", teamId);
        return Jwts.builder()
                .subject(username)
                .claims(claims)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirationMs() {
        return expirationMs;
    }
}
