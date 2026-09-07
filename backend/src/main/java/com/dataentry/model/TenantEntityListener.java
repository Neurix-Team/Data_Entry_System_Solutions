package com.dataentry.model;

import com.dataentry.security.TenantContext;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TenantEntityListener {

    private static final Logger log = LoggerFactory.getLogger(TenantEntityListener.class);

    @PrePersist
    public void beforeInsert(Object entity) {
        if (!(entity instanceof TeamOwned owned)) return;
        if (owned.getTeam() != null) return;
        if (TenantContext.isSuperAdmin()) return;
        Long teamId = TenantContext.getTeamId();
        if (teamId == null) return;
        owned.setTeam(Team.builder().id(teamId).build());
    }

    @PostLoad
    public void afterLoad(Object entity) {
        if (!(entity instanceof TeamOwned owned)) return;
        if (TenantContext.isSuperAdmin()) return;
        Long expected = TenantContext.getTeamId();
        if (expected == null) return;
        Team owner = owned.getTeam();
        if (owner == null || owner.getId() == null) return;
        if (!expected.equals(owner.getId())) {
            log.debug("Cross-team load on {} (owner={}, caller={})",
                    entity.getClass().getSimpleName(), owner.getId(), expected);
        }
    }
}
