package com.dataentry.security;

import com.dataentry.model.Role;

/**
 * Per-request tenant scope. Populated by {@link JwtAuthFilter} after the JWT is parsed,
 * and read by {@code TenantFilterAspect} to enable Hibernate's {@code teamFilter} for the
 * duration of every {@code @Transactional} method call on the request thread.
 *
 * <p>SUPER_ADMIN bypasses the filter entirely — they see and can act on every team. When a
 * SUPER_ADMIN "enters" a team through the impersonation endpoint, a short-lived JWT is
 * issued with role={@code ADMIN} and the target team_id, so the same request context still
 * applies (they behave exactly like a team admin during that session).
 *
 * <p>ThreadLocal cleanup is done inside {@link JwtAuthFilter#doFilterInternal} in a
 * finally block so a leaked value can't contaminate the next request served by the same
 * container thread.
 */
public final class TenantContext {

    public record Snapshot(Long teamId, Role role, Long userId, Long impersonatedBy) {}

    private static final ThreadLocal<Snapshot> HOLDER = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Long teamId, Role role, Long userId, Long impersonatedBy) {
        HOLDER.set(new Snapshot(teamId, role, userId, impersonatedBy));
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static Snapshot snapshot() {
        return HOLDER.get();
    }

    public static Long getTeamId() {
        Snapshot s = HOLDER.get();
        return s == null ? null : s.teamId();
    }

    public static Long getUserId() {
        Snapshot s = HOLDER.get();
        return s == null ? null : s.userId();
    }

    public static Role getRole() {
        Snapshot s = HOLDER.get();
        return s == null ? null : s.role();
    }

    public static boolean isSuperAdmin() {
        Snapshot s = HOLDER.get();
        return s != null && s.role() == Role.SUPER_ADMIN;
    }

    /** True when a SUPER_ADMIN entered a team via {@code /api/super/teams/{id}/enter}. */
    public static boolean isImpersonating() {
        Snapshot s = HOLDER.get();
        return s != null && s.impersonatedBy() != null;
    }

    /** Actor id for audit logs — the super admin id when impersonating, otherwise the caller. */
    public static Long auditActorId() {
        Snapshot s = HOLDER.get();
        if (s == null) return null;
        return s.impersonatedBy() != null ? s.impersonatedBy() : s.userId();
    }
}
