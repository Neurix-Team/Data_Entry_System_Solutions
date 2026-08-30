package com.dataentry.security;

import com.dataentry.model.Team;
import com.dataentry.model.TeamOwned;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Central checkpoint for "am I allowed to act on this TeamOwned row?".
 *
 * <p>Historically enforcement lived entirely inside {@link com.dataentry.model.TenantEntityListener}'s
 * {@code @PostLoad} hook, which threw a hard 404 for any cross-team load. That was
 * defense-in-depth against {@code EntityManager.find} bypassing the Hibernate
 * {@code teamFilter}, but it also fired on incidental lazy loads through {@code @ManyToOne}
 * associations (e.g. {@code Department.project} pointing at another team's project after a
 * legacy data migration). One mismatched FK anywhere in the graph took down the whole list
 * response with a bare "Not found" — the user-visible bug we are fixing here.
 *
 * <p>The new arrangement:
 * <ul>
 *   <li>The {@code @PostLoad} hook only <em>logs</em> a mismatch and lets the load
 *       proceed, so incidental cross-team FKs no longer abort the request.</li>
 *   <li>Every mutation service method (create / update / delete) that loads a
 *       {@link TeamOwned} entity by primary key must call {@link #assertOwnership(TeamOwned)}
 *       immediately after the {@code findById}. That preserves the original invariant
 *       (a team admin cannot mutate another team's row) without the collateral damage.</li>
 * </ul>
 */
public final class TenantGuard {

    private TenantGuard() {}

    /**
     * Verify the loaded entity actually belongs to the caller's tenant. Throws 404 to avoid
     * disclosing that a row with the given id exists in another team.
     *
     * <p>Rules:
     * <ul>
     *   <li>SUPER_ADMIN (not impersonating) sees every team — always allowed.</li>
     *   <li>No auth context (seeder, tests, health probes) — allowed.</li>
     *   <li>Entity's team is {@code null} (legacy row, unowned lookup) — allowed.</li>
     *   <li>Otherwise the team ids must match.</li>
     * </ul>
     */
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
