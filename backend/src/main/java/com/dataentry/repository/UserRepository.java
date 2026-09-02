package com.dataentry.repository;

import com.dataentry.model.Role;
import com.dataentry.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);

    /** Global cross-tenant role lookup. Used by SUPER_ADMIN endpoints. */
    List<User> findAllByRole(Role role);

    /** Every user attached to a specific team. Ordered newest-first for display. */
    List<User> findAllByTeamIdOrderByCreatedAtDesc(Long teamId);

    /**
     * Presence of an admin inside a team. Used to enforce the "one admin per team" invariant
     * — every team is a single admin's isolated workspace, so a second ADMIN can't be added
     * to a team that already has one.
     */
    boolean existsByTeamIdAndRole(Long teamId, Role role);

    /** Every admin in a given team, oldest-first — used by the split migration for teams
     *  that historically ended up with more than one ADMIN. */
    List<User> findAllByTeamIdAndRoleOrderByCreatedAtAsc(Long teamId, Role role);

    /**
     * Members of a project that also belong to the given team. Used by the admin projects
     * view — the raw {@code project.members} collection can contain cross-team users
     * from legacy data, and lazy-loading it trips the {@link com.dataentry.model.TenantEntityListener}
     * PostLoad guard which turns the whole list into a 404. Scoping the load to
     * {@code (project_id, team_id)} avoids that.
     */
    @org.springframework.data.jpa.repository.Query(
            "select u from User u join u.team t "
                    + "where t.id = :teamId "
                    + "and u.id in (select m.id from Project p join p.members m where p.id = :projectId)")
    List<User> findMembersOfProjectInTeam(
            @org.springframework.data.repository.query.Param("projectId") Long projectId,
            @org.springframework.data.repository.query.Param("teamId") Long teamId);

    /** Batch variant so a list page can preload every project's members in one query. */
    @org.springframework.data.jpa.repository.Query(
            "select p.id as projectId, u "
                    + "from Project p join p.members u "
                    + "where p.id in :projectIds and u.team.id = :teamId")
    List<Object[]> findMembersOfProjectsInTeam(
            @org.springframework.data.repository.query.Param("projectIds") java.util.Collection<Long> projectIds,
            @org.springframework.data.repository.query.Param("teamId") Long teamId);
}
