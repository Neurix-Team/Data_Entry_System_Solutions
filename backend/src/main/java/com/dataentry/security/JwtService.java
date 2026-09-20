package com.dataentry.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
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

    /**
     * Marks a token issued after password verification but before the second factor.
     * {@link JwtAuthFilter} refuses to build a session out of one.
     */
    public static final String MFA_PENDING_CLAIM = "mfa_pending";

    private final SecretKey key;
    private final long expirationMs;
    private final long mfaPendingExpirationMs;

    private static final long DEFAULT_EXPIRATION_MS = 86_400_000L;
    private static final long DEFAULT_MFA_PENDING_EXPIRATION_MS = 300_000L;

    /**
     * Spring only applies a placeholder default when the variable is unset, so a blank
     * {@code JWT_EXPIRATION_MS=} (which .env.example and docker-compose both produce) would
     * reach a {@code long} parameter as "" and stop the backend from booting. Read the
     * lifetimes as text and fall back to the defaults when they are blank.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-ms:}") String expirationMs,
                      @Value("${app.jwt.mfa-pending-expiration-ms:}") String mfaPendingExpirationMs) {
        this(secret,
                parseMs(expirationMs, DEFAULT_EXPIRATION_MS),
                parseMs(mfaPendingExpirationMs, DEFAULT_MFA_PENDING_EXPIRATION_MS));
    }

    private static long parseMs(String raw, long fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Token lifetime must be a number of milliseconds, got '" + raw + "'.");
        }
    }

    public JwtService(String secret, long expirationMs, long mfaPendingExpirationMs) {
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
        this.mfaPendingExpirationMs = mfaPendingExpirationMs > 0 ? mfaPendingExpirationMs : 300_000L;
    }

    /** Convenience for tests and tooling: default five-minute pending-ticket lifetime. */
    public JwtService(String secret, long expirationMs) {
        this(secret, expirationMs, 300_000L);
    }

    public String generateToken(String username, String role, Long userId, Long teamId, long tokenVersion) {
        return generateToken(username, role, userId, teamId, tokenVersion, false);
    }

    /**
     * @param mfaPending true for the half-authenticated token handed out while a second
     *                   factor is still owed. Those tokens live minutes, not hours, and the
     *                   filter only honours them on the MFA endpoints.
     */
    public String generateToken(String username, String role, Long userId, Long teamId,
                                long tokenVersion, boolean mfaPending) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + (mfaPending ? mfaPendingExpirationMs : expirationMs));
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        claims.put("uid", userId);
        if (teamId != null) claims.put("tid", teamId);
        claims.put("tv", tokenVersion);
        if (mfaPending) claims.put(MFA_PENDING_CLAIM, Boolean.TRUE);
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

    /**
     * Parses a signed token and returns its claims even when the token has expired.
     * The signature is always verified first — an expired-but-valid token is the only
     * lenient case (used by the refresh flow); tampered or malformed tokens throw.
     */
    public Claims parseLenient(String token) {
        try {
            return parse(token);
        } catch (ExpiredJwtException e) {
            return e.getClaims();
        }
    }

        public long getExpirationMs() {
        return expirationMs;
    }

    /** Lifetime, in ms, of an MFA-pending (half-authenticated) token. */
    public long getMfaPendingExpirationMs() {
        return mfaPendingExpirationMs;
    }

    /** Issue the short-lived token handed out while the second factor is still owed. */
    public String generateMfaPendingToken(String username, String role, Long userId,
                                          Long teamId, long tokenVersion) {
        return generateToken(username, role, userId, teamId, tokenVersion, true);
    }
}
