package com.dataentry.service;

import com.dataentry.model.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Decides whether a given account is allowed to sign in with a password alone.
 *
 * <p>Policy (configurable per deployment):</p>
 * <ul>
 *   <li>{@code app.mfa.required-for-privileged:true} — every ADMIN/SUPER_ADMIN account must
 *       have MFA enabled before a session token is issued. A privileged account with no
 *       device enrolled gets a first-run enrollment ticket instead of a session, so
 *       enforcement can be switched on without locking every operator out.</li>
 *   <li>USER accounts are never forced; MFA is opt-in for them.</li>
 * </ul>
 *
 * <p>Requiring MFA for privileged roles closes the exact gap the last security audit
 * (F-03) called out: a compromised SUPER_ADMIN/ADMIN password yields full access. The
 * factor makes that a single factor instead of two.</p>
 */
@Service
public class MfaPolicy {

    private final boolean requireForPrivileged;

    public MfaPolicy(@Value("${app.mfa.required-for-privileged:true}") boolean requireForPrivileged) {
        this.requireForPrivileged = requireForPrivileged;
    }

    /** The second factor is mandatory for this account at sign-in time. */
    public boolean isRequired(Role role, boolean enrolled) {
        if (requireForPrivileged && Role.SUPER_ADMIN == role) return true;
        if (requireForPrivileged && Role.ADMIN == role) return true;
        return enrolled;
    }

    public boolean isPrivileged(Role role) {
        return Role.SUPER_ADMIN == role || Role.ADMIN == role;
    }
}
