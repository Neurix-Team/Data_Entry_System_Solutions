package com.dataentry.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * RFC 6238 (TOTP) over RFC 4226 (HOTP), HMAC-SHA1 — the dialect Google Authenticator,
 * Authy, 1Password and Bitwarden all speak.
 *
 * <p>The service is stateless on purpose: the caller owns the shared secret and the
 * last-accepted timestep. {@link #verify(String, String, Long)} returns the timestep it
 * accepted so the caller can persist it and make replay of a code inside the acceptance
 * window impossible.</p>
 */
@Component
public class TotpService {

    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int SECRET_BYTES = 20; // 160 bits — RFC 4226 §4 recommendation

    private final int digits;
    private final int periodSeconds;
    private final int windowSteps;
    private final SecureRandom random = new SecureRandom();

    public TotpService(@Value("${app.mfa.digits:6}") int digits,
                       @Value("${app.mfa.period-seconds:30}") int periodSeconds,
                       @Value("${app.mfa.window-steps:1}") int windowSteps) {
        if (digits < 6 || digits > 10) {
            throw new IllegalStateException("app.mfa.digits must be between 6 and 10 (got " + digits + ").");
        }
        if (periodSeconds < 5) {
            throw new IllegalStateException("app.mfa.period-seconds must be at least 5.");
        }
        if (windowSteps < 0 || windowSteps > 2) {
            throw new IllegalStateException(
                    "app.mfa.window-steps must be 0-2 — a wider window only widens the replay surface.");
        }
        this.digits = digits;
        this.periodSeconds = periodSeconds;
        this.windowSteps = windowSteps;
    }

    /** Number of digits in a generated code (6 by default). */
    public int digits() {
        return digits;
    }

    public int periodSeconds() {
        return periodSeconds;
    }

    /** Random 160-bit secret, Base32 (RFC 4648, unpadded) — what authenticator apps expect. */
    public String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public long currentStep() {
        return stepAt(System.currentTimeMillis());
    }

    public long stepAt(long epochMillis) {
        return Math.floorDiv(epochMillis, periodSeconds * 1000L);
    }

    /** The code for an exact timestep — used by tests and tooling, never by the login path. */
    public String codeAt(String base32Secret, long step) {
        byte[] hash = hmac(base32Decode(base32Secret), counterBytes(step));
        int offset = hash[hash.length - 1] & 0x0F;
        long binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        long modulus = 1;
        for (int i = 0; i < digits; i++) modulus *= 10;
        String value = Long.toString(binary % modulus);
        StringBuilder sb = new StringBuilder(digits);
        for (int i = value.length(); i < digits; i++) sb.append('0');
        return sb.append(value).toString();
    }

    public String currentCode(String base32Secret) {
        return codeAt(base32Secret, currentStep());
    }

    /**
     * Validates a submitted code against the clock window.
     *
     * @param lastAcceptedStep highest timestep already consumed for this account (null = none)
     * @return the accepted timestep, or -1 when no valid, unused code matched. Callers persist
     *         the returned timestep so a code stays unusable for the rest of its window.
     */
    public long verify(String base32Secret, String code, Long lastAcceptedStep) {
        if (base32Secret == null || base32Secret.isBlank() || !looksLikeCode(code)) return -1L;
        String normalized = code.trim();
        long now = currentStep();
        long floor = lastAcceptedStep == null ? Long.MIN_VALUE : lastAcceptedStep;
        for (long step = now - windowSteps; step <= now + windowSteps; step++) {
            if (step <= floor) continue;
            if (constantTimeEquals(normalized, codeAt(base32Secret, step))) return step;
        }
        return -1L;
    }

    public boolean looksLikeCode(String code) {
        if (code == null) return false;
        String trimmed = code.trim();
        if (trimmed.length() != digits) return false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /**
     * otpauth:// URI an authenticator app consumes (as text or as a QR payload). The secret
     * travels to the browser exactly once — during enrollment — and never again.
     */
    public String otpauthUri(String issuer, String account, String base32Secret) {
        return "otpauth://totp/" + urlEncode(issuer) + ":" + urlEncode(account)
                + "?secret=" + base32Secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1"
                + "&digits=" + digits
                + "&period=" + periodSeconds;
    }

    private byte[] counterBytes(long step) {
        return ByteBuffer.allocate(Long.BYTES).putLong(step).array();
    }

    private byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA1 unavailable.", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** RFC 4648 Base32 without padding, uppercase — the shape authenticator apps expect. */
    static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return out.toString();
    }

    /** Decodes Base32; tolerates lowercase, spaces, `-` grouping and stray `=` padding. */
    static byte[] base32Decode(String encoded) {
        if (encoded == null) throw new IllegalStateException("Secret is missing.");
        String cleaned = encoded.trim().replace(" ", "").replace("=", "")
                .toUpperCase(Locale.ROOT);
        if (cleaned.isEmpty()) throw new IllegalStateException("Secret is empty.");
        ByteArrayOutputStream out = new ByteArrayOutputStream(cleaned.length() * 5 / 8);
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == '-') continue; // grouped secrets such as "ABCD-EFGH-IJKL"
            int value = BASE32_ALPHABET.indexOf(c);
            if (value < 0) throw new IllegalStateException("Secret is not valid Base32.");
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
