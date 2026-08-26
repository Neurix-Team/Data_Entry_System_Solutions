package com.dataentry.repository;

import com.dataentry.model.Project;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    // No EntityGraph on the list queries below. Eagerly fetching members joins in every
    // project_members row — legacy data can contain cross-team member references, and the
    // TenantEntityListener's @PostLoad throws NOT_FOUND on the first cross-team User row,
    // turning the whole /api/admin/projects response into a 404. Members are lazy-loaded
    // per project inside the @Transactional service call instead, where the teamFilter
    // scopes the load to the current team.

    List<Project> findAllByOrderByCreatedAtDesc();

    Optional<Project> findWithMembersById(Long id);

    /** All projects a specific user is a member of (ordered by creation, newest first). */
    @org.springframework.data.jpa.repository.Query(
            "select p from Project p join p.members m where m.id = :userId order by p.createdAt desc")
    List<Project> findAllByMemberId(@org.springframework.data.repository.query.Param("userId") Long userId);

    // ---- Project-folders view: leaner queries that don't eagerly load members / dept ----
    //
    // The @EntityGraph on findAllByOrderByCreatedAtDesc pulls in every project member. When
    // legacy data (from before per-team scoping) has cross-team users on a members list, the
    // tenant listener's @PostLoad fires on the wrong-team user and turns the whole folder
    // list into a 404. The folder view doesn't render members/dept, so skip the fetch here.

    @org.springframework.data.jpa.repository.Query(
            "select p from Project p order by p.createdAt desc")
    List<Project> findAllForFolderView();

    @org.springframework.data.jpa.repository.Query(
            "select p from Project p join p.members m where m.id = :userId order by p.createdAt desc")
    List<Project> findMemberProjectsForFolderView(@org.springframework.data.repository.query.Param("userId") Long userId);

    /**
     * Membership check without loading the {@code members} collection. Used by the folder
     * detail endpoint so a USER hitting a folder they're a member of doesn't accidentally
     * pull in a cross-team member row and 404.
     */
    @org.springframework.data.jpa.repository.Query(
            "select count(p) > 0 from Project p join p.members m where p.id = :projectId and m.id = :userId")
    boolean isMember(@org.springframework.data.repository.query.Param("projectId") Long projectId,
                     @org.springframework.data.repository.query.Param("userId") Long userId);
}
