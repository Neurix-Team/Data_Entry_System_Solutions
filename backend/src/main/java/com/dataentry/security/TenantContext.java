package com.dataentry.security;

import com.dataentry.model.Role;

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

    public static boolean isImpersonating() {
        Snapshot s = HOLDER.get();
        return s != null && s.impersonatedBy() != null;
    }

    public static Long auditActorId() {
        Snapshot s = HOLDER.get();
        if (s == null) return null;
        return s.impersonatedBy() != null ? s.impersonatedBy() : s.userId();
    }
}
