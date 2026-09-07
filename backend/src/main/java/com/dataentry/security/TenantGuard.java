package com.dataentry.security;

import com.dataentry.model.Team;
import com.dataentry.model.TeamOwned;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class TenantGuard {

    private TenantGuard() {}

    public static void assertOwnership(TeamOwned entity) {
        if (entity == null) return;
        if (TenantContext.isSuperAdmin()) return;
        Long expected = TenantContext.getTeamId();
        if (expected == null) return;
        Team owner = entity.getTeam();
        if (owner == null || owner.getId() == null) return;
        if (!expected.equals(owner.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
    }
}
