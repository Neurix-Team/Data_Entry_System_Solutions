package com.dataentry.security;

import com.dataentry.model.Team;
import com.dataentry.model.TeamOwned;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class TenantGuard {

    private TenantGuard() {}

    public static void assertOwnership(TeamOwned entity) {
        if (entity == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (TenantContext.isSuperAdmin()) return;
        Long expected = TenantContext.getTeamId();
        Team owner = entity.getTeam();
        if (expected == null || owner == null || owner.getId() == null
                || !expected.equals(owner.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
    }
}
