package com.dataentry.model;

import com.dataentry.security.TenantContext;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Belt-and-suspenders tenant enforcement on {@link TeamOwned} entities.
 *
 * <p><b>{@link PrePersist}</b> — Stamps the current tenant's {@code team_id} on any new
 * row so a caller can't accidentally (or maliciously) create a row in another team by
 * omitting the field. Skipped for SUPER_ADMIN because they legitimately create global
 * things (like teams themselves) with no owning team.
 *
 * <p><b>{@link PostLoad}</b> — Purely observational: logs a warning when a TeamOwned row
 * loaded on a scoped request belongs to a different team than the caller. It does <em>not</em>
 * throw. Previously it did throw a hard 404, which was correct for direct
 * {@code repository.findById} bypass attempts but also fired on incidental lazy loads
 * through {@code @ManyToOne} associations (e.g. {@code Department.project} pointing at
 * another team's project after a legacy migration), aborting whole list endpoints with an
 * opaque "Not found". Mutation authorisation is now enforced explicitly by
 * {@link com.dataentry.security.TenantGuard#assertOwnership(TeamOwned)} in every write-path
 * service method; the Hibernate {@code teamFilter} continues to scope list queries.
 */
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
            // Log-only. Enable com.dataentry.model.TenantEntityListener=DEBUG to trace the
            // origin of a cross-team load; the mutation-side TenantGuard is what actually
            // blocks unauthorised writes.
            log.debug("Cross-team load on {} (owner={}, caller={})",
                    entity.getClass().getSimpleName(), owner.getId(), expected);
        }
    }
}
