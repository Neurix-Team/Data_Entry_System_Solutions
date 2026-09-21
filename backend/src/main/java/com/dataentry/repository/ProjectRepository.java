package com.dataentry.repository;

import com.dataentry.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /**
     * Recycle bin: Project carries no class-level @Where (live tickets must keep
     * resolving binned projects), so every read below filters deleted rows out
     * explicitly. findById deliberately still returns binned rows — callers decide
     * (services guard writes; the recycle bin reads them on purpose).
     */
    @Query("select p from Project p where p.deletedAt is null order by p.createdAt desc")
    List<Project> findAllByOrderByCreatedAtDesc();

    @Query("select p from Project p where p.id = :id and p.deletedAt is null")
    Optional<Project> findWithMembersById(@Param("id") Long id);

    @Query("select p from Project p join p.members m where m.id = :userId and p.deletedAt is null order by p.createdAt desc")
    List<Project> findAllByMemberId(@Param("userId") Long userId);

    @Query("select p from Project p where p.deletedAt is null order by p.createdAt desc")
    List<Project> findAllForFolderView();

    @Query("select p from Project p join p.members m where m.id = :userId and p.deletedAt is null order by p.createdAt desc")
    List<Project> findMemberProjectsForFolderView(@Param("userId") Long userId);

    @Query("select count(p) > 0 from Project p join p.members m where p.id = :projectId and m.id = :userId and p.deletedAt is null")
    boolean isMember(@Param("projectId") Long projectId,
                     @Param("userId") Long userId);

    // ─── Recycle bin ─────────────────────────────────────────────────────────────
    // Native for parity with TicketRepository so both halves of the bin read the
    // same way. Null teamId means "no tenant restriction" (super admin / sweeper).

    interface DeletedProjectRow {
        Long getId();
        String getName();
        String getNameEn();
        String getNameAr();
        String getStatus();
        Instant getDeletedAt();
        Long getDeletedById();
        String getDeletedByName();
        Long getTeamId();
    }

    @Query(value = """
            SELECT p.id AS "id",
                   COALESCE(p.name_en, p.name_ar, p.name) AS "name",
                   p.name_en AS "nameEn",
                   p.name_ar AS "nameAr",
                   p.status AS "status",
                   p.deleted_at AS "deletedAt",
                   p.deleted_by_id AS "deletedById",
                   COALESCE(db.display_name, db.username) AS "deletedByName",
                   p.team_id AS "teamId"
              FROM projects p
              LEFT JOIN users db ON db.id = p.deleted_by_id
             WHERE p.deleted_at IS NOT NULL
               AND (CAST(:teamId AS BIGINT) IS NULL OR p.team_id = :teamId)
             ORDER BY p.deleted_at DESC
             LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<DeletedProjectRow> findDeletedProjects(@Param("teamId") Long teamId,
                                                @Param("limit") int limit,
                                                @Param("offset") int offset);

    @Query(value = """
            SELECT COUNT(*) FROM projects p
             WHERE p.deleted_at IS NOT NULL
               AND (CAST(:teamId AS BIGINT) IS NULL OR p.team_id = :teamId)
            """, nativeQuery = true)
    long countDeletedProjects(@Param("teamId") Long teamId);

    /** Team of a project, binned or not. */
    @Query(value = "select team_id from projects where id = :id", nativeQuery = true)
    Long teamIdOfAnyProject(@Param("id") Long id);

    /** 1 when the project is actually sitting in the bin, 0 otherwise. */
    @Query(value = "select count(*) from projects where id = :id and deleted_at is not null", nativeQuery = true)
    long countBinnedProjectById(@Param("id") Long id);

    @Modifying
    @Query(value = "update projects set deleted_at = null, deleted_by_id = null "
            + "where id = :id and deleted_at is not null", nativeQuery = true)
    int restoreProject(@Param("id") Long id);

    @Modifying
    @Query(value = "delete from projects where id = :id and deleted_at is not null", nativeQuery = true)
    int purgeProjectRow(@Param("id") Long id);

    @Modifying
    @Query(value = "delete from project_members where project_id = :projectId", nativeQuery = true)
    int purgeProjectMembers(@Param("projectId") Long projectId);

    /** Binned projects past the retention window — the sweeper's worklist. */
    @Query(value = "select id from projects where deleted_at is not null and deleted_at < :cutoff", nativeQuery = true)
    List<Long> findExpiredDeletedProjectIds(@Param("cutoff") Instant cutoff);

    // ─── Global search (Ctrl+K palette) ────────────────────────────────────────
    // Projects carry no @Where, so the deleted filter is explicit here.

    interface SearchRow {
        Long getId();
        String getName();
    }

    @Query(value = """
            SELECT p.id AS "id", COALESCE(p.name_en, p.name_ar, p.name) AS "name"
              FROM projects p
             WHERE p.deleted_at IS NULL
               AND (CAST(:teamId AS BIGINT) IS NULL OR p.team_id = :teamId)
               AND (lower(p.name) LIKE :pattern
                    OR lower(p.name_en) LIKE :pattern
                    OR lower(p.name_ar) LIKE :pattern)
             ORDER BY p.created_at DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<SearchRow> searchProjects(@Param("teamId") Long teamId,
                                   @Param("pattern") String pattern,
                                   @Param("limit") int limit);
}