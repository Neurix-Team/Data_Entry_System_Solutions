package com.dataentry.model;

import com.dataentry.security.TenantContext;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Belt-and-suspenders tenant enforcement on {@link TeamOwned} entities.
 *
 * <p><b>{@link PrePersist}</b> — Stamps the current tenant's {@code team_id} on any new
 * row so a caller can't accidentally (or maliciously) create a row in another team by
 * omitting the field. Skipped for SUPER_ADMIN because they legitimately create global
 * things (like teams themselves) with no owning team.
 *
 * <p><b>{@link PostLoad}</b> — The Hibernate {@code teamFilter} on {@link org.hibernate.annotations.Filter}
 * is only applied to JPQL/HQL queries; primary-key lookups ({@code EntityManager.find},
 * used internally by Spring Data JPA's {@code findById}, {@code deleteById}, {@code save}
 * on merge) bypass it. Without this hook, a team admin could pass any project id to
 * {@code DELETE /admin/projects/{id}} and Spring Data would happily hand back the entity
 * from another team. This callback rejects that access as a 404, matching what the
 * filter would have done during a JPQL list.
 */
public class TenantEntityListener {

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
        if (expected == null) return; // no auth context (seeder, tests, health checks)
        Team owner = owned.getTeam();
        // Reading getId() on a Hibernate lazy proxy does NOT trigger DB load — the FK is
        // kept on the proxy itself, so this check is essentially free.
        if (owner == null || !expected.equals(owner.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
    }
}
