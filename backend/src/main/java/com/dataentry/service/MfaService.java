package com.dataentry.service;

import com.dataentry.dto.MfaDtos;
import com.dataentry.model.MfaRecoveryCode;
import com.dataentry.model.Role;
import com.dataentry.model.User;
import com.dataentry.repository.MfaRecoveryCodeRepository;
import com.dataentry.repository.UserRepository;
import com.dataentry.security.JwtService;
import com.dataentry.security.TotpService;
import com.dataentry.security.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Second-factor (TOTP) orchestration for every entry point: login challenge, first-run
 * enrollment, self-service disable, recovery codes and operator reset.
 *
 * <p>Two independent guards back it. A per-account attempt budget (mfa_failed_attempts /
 * mfa_locked_until, V8) is durable, so a process restart cannot clear a lockout, and the
 * short-lived MFA-pending ticket keeps the password step from being replayed.</p>
 */
@Service
public class MfaService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RECOVERY_CODES = 10;

    @Value("${app.mfa.issuer:DataEntry}")
    private String issuer;

    private final UserRepository userRepository;
    private final MfaRecoveryCodeRepository recoveryRepository;
    private final TotpService totp;
    private final SecretCipher cipher;
    private final JwtService jwtService;
    private final AuditService audit;
    private final MfaPolicy policy;
    private final MfaFailureRecorder failureRecorder;

    public MfaService(UserRepository userRepository,
                      MfaRecoveryCodeRepository recoveryRepository,
                      TotpService totp,
                      SecretCipher cipher,
                      JwtService jwtService,
                      AuditService audit,
                      MfaPolicy policy,
                      MfaFailureRecorder failureRecorder) {
        this.userRepository = userRepository;
        this.recoveryRepository = recoveryRepository;
        this.totp = totp;
        this.cipher = cipher;
        this.jwtService = jwtService;
        this.audit = audit;
        this.policy = policy;
        this.failureRecorder = failureRecorder;
    }

    public int digits() {
        return totp.digits();
    }

    public int periodSeconds() {
        return totp.periodSeconds();
    }

    /** True while the account's second factor is locked; the challenge must not even run. */
    public boolean isLocked(User user) {
        return user != null && user.isMfaLocked();
    }

    /** Result of a challenge attempt, so callers can map to HTTP codes without extra queries. */
    public enum ChallengeResult { OK, INVALID, LOCKED, NOT_ENROLLED, RECOVERED }

    /** Signed, short-lived ticket issued after a correct password but before the second factor. */
    public String issueChallengeTicket(User user) {
        return jwtService.generateMfaPendingToken(user.getUsername(), user.getRole().name(),
                user.getId(), user.getTeam() == null ? null : user.getTeam().getId(),
                user.getTokenVersion());
    }

    /**
     * Verifies a login-time challenge. Replay is impossible inside the acceptance window:
     * the timestep that matched is persisted, and any step at or below it is refused
     * afterwards. A valid, unused recovery code also resolves the challenge and consumes
     * itself.
     */
    @Transactional
    public ChallengeResult verifyChallenge(User user, String code) {
        if (user == null || !user.isActive()) return ChallengeResult.NOT_ENROLLED;
        if (user.isMfaLocked()) return ChallengeResult.LOCKED;
        if (!isEnrolled(user)) return ChallengeResult.NOT_ENROLLED;

        Long recovered = recoveryRepository.findByCodeHash(sha256(code.trim()))
                .filter(c -> c.getUserId().equals(user.getId()) && !c.isUsed())
                .map(c -> {
                    c.setUsedAt(Instant.now());
                    recoveryRepository.save(c);
                    return c.getId();
                })
                .orElse(null);
        if (recovered != null) {
            audit.record(AuditService.Action.STATUS_CHANGE, AuditService.EntityType.USER,
                    user.getId(), "mfaChallenge=recoveryCode");
            return ChallengeResult.RECOVERED;
        }

        long step = totp.verify(secretOf(user), code, user.getMfaLastStep());
        if (step < 0) {
            registerFailure(user);
            return user.isMfaLocked() ? ChallengeResult.LOCKED : ChallengeResult.INVALID;
        }
        user.setMfaLastStep(step);
        clearFailures(user);
        userRepository.save(user);
        return ChallengeResult.OK;
    }

    /** True when this account must complete MFA to obtain a session. */
    public boolean isRequired(User user) {
        return policy.isRequired(user.getRole(), isEnrolled(user));
    }

    /** True when the role mandates MFA regardless of whether a device is enrolled yet. */
    public boolean isMandatory(Role role) {
        return policy.isRequired(role, false);
    }

    /** True when the account already has a confirmed second factor. */
    public boolean isEnrolled(User user) {
        return user != null && user.isMfaEnabled()
                && user.getMfaSecretEncrypted() != null
                && !user.getMfaSecretEncrypted().isBlank();
    }

    /** MFA state of the caller; the shared secret never appears here. */
    public MfaDtos.StatusResponse status(User user) {
        boolean enrolled = isEnrolled(user);
        boolean started = user.getMfaSecretEncrypted() != null
                && !user.getMfaSecretEncrypted().isBlank();
        return new MfaDtos.StatusResponse(
                enrolled,
                started && !enrolled,
                isRequired(user),
                user.isMfaLocked(),
                user.getMfaEnabledAt() != null ? user.getMfaEnabledAt().toString() : null,
                totp.digits(),
                totp.periodSeconds(),
                (int) recoveryRepository.countByUserIdAndUsedAtIsNull(user.getId()));
    }

    /**
     * Starts enrollment. The pending secret is stored encrypted but mfa_enabled stays false
     * until a code from the authenticator proves the device actually works, so a botched
     * enrollment can never lock an account out of its own second factor.
     */
    @Transactional
    public MfaDtos.EnrollResponse beginEnrollment(User user) {
        String secret = totp.newSecret();
        user.setMfaSecretEncrypted(cipher.encrypt(secret));
        user.setMfaEnabled(false);
        user.setMfaEnabledAt(null);
        userRepository.save(user);
        audit.record(AuditService.Action.UPDATE, AuditService.EntityType.USER, user.getId(),
                "mfaEnrollmentStarted=true");
        return new MfaDtos.EnrollResponse(
                secret,
                totp.otpauthUri(issuer, user.getUsername(), secret),
                issuer,
                user.getUsername(),
                totp.digits(),
                totp.periodSeconds());
    }

    /**
     * Confirms enrollment with the first code from the authenticator app. Issues recovery
     * codes and returns their plaintext — shown once, never stored in the clear.
     */
    @Transactional
    public MfaDtos.RecoveryCodesResponse confirmEnrollment(User user, String code) {
        String enc = user.getMfaSecretEncrypted();
        if (enc == null || enc.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Start MFA setup before confirming it.");
        }
        if (user.isMfaLocked()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, lockoutMessage(user));
        }
        long step = totp.verify(secretOf(user), code, null);
        if (step < 0) {
            registerFailure(user);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "That code is not valid. Check your device clock and try the next code.");
        }
        user.setMfaEnabled(true);
        user.setMfaEnabledAt(Instant.now());
        user.setMfaLastStep(step);
        clearFailures(user);
        // Bump the revocation version: sessions that predate the factor must sign in again.
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        List<String> codes = generateRecoveryCodes(user.getId());
        audit.record(AuditService.Action.MFA_ENABLED, AuditService.EntityType.USER, user.getId(),
                "mfaEnabled=true recoveryCodes=" + codes.size());
        return new MfaDtos.RecoveryCodesResponse(codes.size(), codes,
                "Recovery codes issued. Store them somewhere safe.");
    }

    /**
     * Self-service disable. Requires a currently valid code, so a stolen session alone cannot
     * strip the account's second factor. Refused while the role mandates MFA; an opted-in
     * USER may opt back out with a valid code.
     */
    @Transactional
    public void disable(User user, String code) {
        if (isMandatory(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Two-factor authentication is required for " + user.getRole().name()
                            + " accounts and cannot be turned off here.");
        }
        if (!isEnrolled(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Two-factor authentication is not enabled on this account.");
        }
        if (user.isMfaLocked()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, lockoutMessage(user));
        }
        if (totp.verify(secretOf(user), code, user.getMfaLastStep()) < 0) {
            registerFailure(user);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "That code is not valid, so two-factor authentication is still on.");
        }
        clearMfa(user);
        audit.record(AuditService.Action.MFA_DISABLED, AuditService.EntityType.USER, user.getId(),
                "mfaEnabled=false");
    }

    /** Admin or self reset — clears the enrollment and lockout so the account can re-enroll. */
    @Transactional
    public void reset(User user) {
        clearMfa(user);
        audit.record(AuditService.Action.MFA_RESET, AuditService.EntityType.USER, user.getId(),
                "mfaReset=true");
    }

    /** Regenerates recovery codes; the previous set is invalidated at once. */
    @Transactional
    public MfaDtos.RecoveryCodesResponse regenerateRecoveryCodes(User user) {
        if (!isEnrolled(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Enable two-factor authentication before managing recovery codes.");
        }
        recoveryRepository.deleteAllByUserId(user.getId());
        List<String> codes = generateRecoveryCodes(user.getId());
        audit.record(AuditService.Action.MFA_RECOVERY_CODES, AuditService.EntityType.USER,
                user.getId(), "recoveryCodesIssued=" + codes.size());
        return new MfaDtos.RecoveryCodesResponse(codes.size(), codes,
                "Recovery codes regenerated. Old codes no longer work.");
    }

    // --- internals -----------------------------------------------------------------

    private String secretOf(User user) {
        String encrypted = user.getMfaSecretEncrypted();
        if (encrypted == null || encrypted.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "MFA is not set up for this account.");
        }
        return cipher.decrypt(encrypted);
    }

    private void clearMfa(User user) {
        user.setMfaEnabled(false);
        user.setMfaSecretEncrypted(null);
        user.setMfaEnabledAt(null);
        user.setMfaLastStep(null);
        user.setMfaFailedAttempts(0);
        user.setMfaLockedUntil(null);
        // Every outstanding token (session or pending ticket) dies with the factor.
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        recoveryRepository.deleteAllByUserId(user.getId());
    }

    /**
     * Failure counting happens in its own committed transaction (MfaFailureRecorder), so
     * the 401 thrown after a miss can never roll the attempt counter — or the lockout it
     * trips — back to zero.
     */
    private void registerFailure(User user) {
        failureRecorder.register(user);
    }

    private void clearFailures(User user) {
        user.setMfaFailedAttempts(0);
        user.setMfaLockedUntil(null);
    }

    public String lockoutMessage(User user) {
        Duration remaining = Duration.between(Instant.now(), user.getMfaLockedUntil());
        long minutes = Math.max(1, (remaining.toSeconds() + 59) / 60);
        return "Too many invalid codes. Try again in about " + minutes + " minute"
                + (minutes == 1 ? "." : "s.")
                + (user.isAdminLike() && !isEnrolled(user)
                        ? " A super admin can clear this account's lock from the admin console." : "");
    }

    private List<String> generateRecoveryCodes(Long userId) {
        List<MfaRecoveryCode> entities = new ArrayList<>(RECOVERY_CODES);
        List<String> plaintext = new ArrayList<>(RECOVERY_CODES);
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String code = String.format("%03d", RANDOM.nextInt(1000))
                    + "-" + String.format("%03d", RANDOM.nextInt(1000));
            entities.add(MfaRecoveryCode.builder()
                    .userId(userId)
                    .codeHash(sha256(code))
                    .build());
            plaintext.add(code);
        }
        recoveryRepository.saveAll(entities);
        return plaintext;
    }

    private static String sha256(String value) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable.", e);
        }
    }
}
