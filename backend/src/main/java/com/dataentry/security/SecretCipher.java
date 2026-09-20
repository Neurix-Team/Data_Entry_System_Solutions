package com.dataentry.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Authenticated at-rest encryption for small secrets that must stay useless to anyone
 * who can read the database (today: TOTP shared secrets).
 *
 * <p>Key derivation is SHA-256(DOMAIN + JWT_SECRET). The domain prefix gives a key that is
 * cryptographically independent from the JWT signing key, so one operator secret can back
 * both without a leak in one area transferring to the other.</p>
 *
 * <p>Format: Base64(nonce[12] || ciphertext || tag[16]). A fresh random nonce per call means
 * encrypting the same secret twice yields different payloads; the GCM tag makes tampering a
 * hard failure rather than a silent corrupt read.</p>
 */
@Component
public class SecretCipher {

    static final String DOMAIN = "neurix:at-rest:v1:mfa:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${app.jwt.secret}") String jwtSecret) {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "app.jwt.secret is required to derive the at-rest encryption key.");
        }
        this.key = new SecretKeySpec(sha256(DOMAIN + jwtSecret), "AES");
    }

    /** Encrypts a value for storage. Returns null for null so nullable columns stay null. */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(sealed, 0, payload, nonce.length, sealed.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt secret at rest.", e);
        }
    }

    /**
     * Decrypts a stored value. Fails closed: a tampered payload, a truncated one, or a value
     * encrypted under a different JWT_SECRET raises {@link IllegalStateException} instead of
     * returning partial data.
     */
    public String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Stored secret is not valid Base64.", e);
        }
        if (raw.length <= NONCE_LENGTH) {
            throw new IllegalStateException("Stored secret is truncated.");
        }
        try {
            byte[] nonce = Arrays.copyOfRange(raw, 0, NONCE_LENGTH);
            byte[] sealed = Arrays.copyOfRange(raw, NONCE_LENGTH, raw.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Stored secret could not be decrypted (wrong JWT_SECRET or tampered row).", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable.", e);
        }
    }
}
