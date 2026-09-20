package com.dataentry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * MFA (TOTP) payloads - the shapes the MFA controller and the frontend trade.
 *
 * <p>Two independent flows share these:</p>
 * <ul>
 *   <li><b>Sign-in challenge</b> - the password step returns {@code AuthDtos.LoginResponse}
 *       with {@code mfaRequired=true} and an {@code mfaTicket}; {@code MfaVerifyRequest}
 *       consumes it at {@code POST /api/auth/mfa/verify}.</li>
 *   <li><b>Enrollment</b> - an authenticated user enrolls a device and reads the shared secret
 *       exactly once, then confirms with a code before it is enforced.</li>
 * </ul>
 */
public class MfaDtos {

    /**
     * A 6-10 digit TOTP code (backend default is 6), or a recovery code in its
     * {@code ddd-ddd} shape — the challenge step accepts either.
     */
    public static final String CODE_PATTERN = "^\\s*([0-9]{6,10}|[0-9]{3}-[0-9]{3})\\s*$";

    /** One-time ticket + code from an authenticator app, or a single-use recovery code. */
    public record VerifyRequest(
            @NotBlank @Size(max = 2048) String ticket,

            @NotBlank
            @Pattern(regexp = CODE_PATTERN, message = "Enter the digits from your authenticator app")
            String code
    ) {}

    /** Enrollment start: proves the caller still knows the account password. */
    public record EnrollRequest(
            @NotBlank @Size(max = 200) String password
    ) {}

    /**
     * Enrollment material. {@code secret} and {@code otpauthUri} are returned once, before the
     * device is confirmed, and are never exposed again.
     */
    public record EnrollResponse(
            String secret,
            String otpauthUri,
            String issuer,
            String account,
            int digits,
            int periodSeconds
    ) {}

    /** Enrollment confirmation, or a repeat challenge while enabling/disabling. */
    public record CodeRequest(
            @NotBlank
            @Pattern(regexp = CODE_PATTERN, message = "Enter the digits from your authenticator app")
            String code
    ) {}

    /** MFA state of the caller; the secret never appears here. */
    public record StatusResponse(
            boolean enrolled,
            boolean pendingEnrollment,
            boolean required,
            boolean locked,
            String enabledAt,
            int digits,
            int periodSeconds,
            int recoveryCodesRemaining
    ) {}

    /** Issued once when a user enrolls or regenerates recovery codes. */
    public record RecoveryCodesResponse(int count, List<String> codes, String message) {}

    /** Operator view: which accounts are behind a second factor. */
    public record AccountMfaRow(
            Long id,
            String username,
            String displayName,
            String role,
            boolean mfaEnabled,
            String mfaEnabledAt,
            long recoveryCodesLeft
    ) {}
}
