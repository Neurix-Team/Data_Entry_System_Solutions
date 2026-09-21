package com.dataentry.repository;

import com.dataentry.dto.DashboardDtos;
import com.dataentry.model.Ticket;
import com.dataentry.model.TicketStatus;
import com.dataentry.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    interface AdminStatsProjection {
        long getTotalTickets();
        long getTotalDepartments();
        long getActiveFields();
        long getTotalUsers();
        long getInProgress();
        long getReview();
        long getCompleted();
        long getCompletedToday();
    }

    interface DepartmentDailyCountProjection {
        Long getDepartmentId();
        java.time.LocalDate getDay();
        long getTotal();
    }

    interface WeeklySummaryProjection {
        java.time.LocalDate getDay();
        long getTotal();
        long getCompleted();
    }

    interface LeaderboardAggregateProjection {
        Long getUserId();
        String getUsername();
        String getDisplayName();
        String getDisplayNameEn();
        String getDisplayNameAr();
        long getTotal();
        long getTodayCount();
        long getWeekCount();
    }

    @Query(value = """
            SELECT
              (SELECT COUNT(*) FROM tickets
                WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId)
                  AND deleted_at IS NULL) AS "totalTickets",
              (SELECT COUNT(*) FROM departments
                WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId)) AS "totalDepartments",
              (SELECT COUNT(*) FROM custom_fields
                WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId) AND active = TRUE)
                AS "activeFields",
              (SELECT COUNT(*) FROM users
                WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId)) AS "totalUsers",
              (SELECT COUNT(*) FROM tickets
                WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId) AND status = 'IN_PROGRESS'
                  AND deleted_at IS NULL)
                AS "inProgress",
              (SELECT COUNT(*) FROM tickets
                WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId) AND status = 'REVIEW'
                  AND deleted_at IS NULL)
                AS "review",
              (SELECT COUNT(*) FROM tickets
                WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId) AND status = 'COMPLETED'
                  AND deleted_at IS NULL)
                AS "completed",
              (SELECT COUNT(*) FROM tickets
                WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId)
                  AND status = 'COMPLETED' AND submitted_at >= :startOfToday
                  AND deleted_at IS NULL)
                AS "completedToday"
            """, nativeQuery = true)
    AdminStatsProjection aggregateAdminStats(@Param("teamId") Long teamId,
                                             @Param("startOfToday") Instant startOfToday);

    @Query(value = """
            SELECT department_id AS "departmentId",
                   CAST(submitted_at AT TIME ZONE :zoneId AS date) AS "day",
                   COUNT(*) AS "total"
              FROM tickets
             WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId)
               AND submitted_at >= :since
                AND deleted_at IS NULL
             GROUP BY 1, 2
            """, nativeQuery = true)
    List<DepartmentDailyCountProjection> departmentDailyCounts(
            @Param("teamId") Long teamId,
            @Param("since") Instant since,
            @Param("zoneId") String zoneId);

    @Query(value = """
            SELECT CAST(submitted_at AT TIME ZONE :zoneId AS date) AS "day",
                   COUNT(*) AS "total",
                   COUNT(*) FILTER (WHERE status = 'COMPLETED') AS "completed"
              FROM tickets
             WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId)
               AND submitted_at >= :since
                AND deleted_at IS NULL
             GROUP BY 1
             ORDER BY 1
            """, nativeQuery = true)
    List<WeeklySummaryProjection> weeklySummary(
            @Param("teamId") Long teamId,
            @Param("since") Instant since,
            @Param("zoneId") String zoneId);

    @Query(value = """
            SELECT u.id AS "userId",
                   u.username AS "username",
                   u.display_name AS "displayName",
                   u.display_name_en AS "displayNameEn",
                   u.display_name_ar AS "displayNameAr",
                   COUNT(t.id) FILTER (WHERE t.submitted_at >= :rangeStart) AS "total",
                   COUNT(t.id) FILTER (WHERE t.submitted_at >= :todayStart) AS "todayCount",
                   COUNT(t.id) FILTER (WHERE t.submitted_at >= :weekStart) AS "weekCount"
              FROM users u
              LEFT JOIN tickets t
                     ON t.submitted_by_id = u.id
                    AND t.deleted_at IS NULL
                    AND (CAST(:teamId AS BIGINT) IS NULL OR t.team_id = :teamId)
             WHERE (CAST(:teamId AS BIGINT) IS NULL OR u.team_id = :teamId)
             GROUP BY u.id, u.username, u.display_name, u.display_name_en, u.display_name_ar
            HAVING COUNT(t.id) FILTER (WHERE t.submitted_at >= :rangeStart) > 0
             ORDER BY COUNT(t.id) FILTER (WHERE t.submitted_at >= :rangeStart) DESC,
                      LOWER(u.username), u.id
            """, nativeQuery = true)
    List<LeaderboardAggregateProjection> leaderboardAggregate(
            @Param("teamId") Long teamId,
            @Param("rangeStart") Instant rangeStart,
            @Param("todayStart") Instant todayStart,
            @Param("weekStart") Instant weekStart);

    @EntityGraph(attributePaths = {"customValues", "customValues.field", "department", "subcategory", "submittedBy"})
    Page<Ticket> findAllBySubmittedByOrderBySubmittedAtDesc(User submittedBy, Pageable pageable);

    @EntityGraph(attributePaths = {"customValues", "customValues.field", "department", "subcategory", "submittedBy"})
    Page<Ticket> findAllByOrderBySubmittedAtDesc(Pageable pageable);

    @Query(value = "select t.id from Ticket t order by t.submittedAt desc",
           countQuery = "select count(t) from Ticket t")
    Page<Long> findAdminPageIds(Pageable pageable);

    @Query(value = "select t.id from Ticket t where t.submittedBy.id = :userId order by t.submittedAt desc",
           countQuery = "select count(t) from Ticket t where t.submittedBy.id = :userId")
    Page<Long> findUserPageIds(@Param("userId") Long userId, Pageable pageable);

    @EntityGraph(attributePaths = {
            "customValues", "customValues.field", "department", "subcategory", "project", "submittedBy"
    })
    @Query("select distinct t from Ticket t where t.id in :ids")
    List<Ticket> findListDetailsByIdIn(@Param("ids") java.util.Collection<Long> ids);

    @EntityGraph(attributePaths = {"customValues", "customValues.field", "department", "subcategory", "submittedBy"})
    Optional<Ticket> findWithDetailsById(Long id);

    long countBySubmittedBy(User submittedBy);

    long countBySubcategoryId(Long subcategoryId);

    List<Ticket> findAllByDepartmentId(Long departmentId);

    List<Ticket> findAllBySubcategoryId(Long subcategoryId);

    List<Ticket> findAllByProjectId(Long projectId);


    @EntityGraph(attributePaths = {"customValues", "customValues.field", "department", "subcategory", "project", "submittedBy"})
    List<Ticket> findAllByProjectIdOrderBySubmittedAtDesc(Long projectId);

    @EntityGraph(attributePaths = {"customValues", "customValues.field", "department", "subcategory", "project", "submittedBy"})
    List<Ticket> findAllByProjectIdAndSubmittedByIdOrderBySubmittedAtDesc(Long projectId, Long userId);

    long countByProjectId(Long projectId);

    long countByProjectIdAndStatus(Long projectId, TicketStatus status);

    long countByProjectIdAndSubmittedById(Long projectId, Long userId);

    long countByProjectIdAndSubmittedByIdAndStatus(Long projectId, Long userId, TicketStatus status);


    long countByStatus(TicketStatus status);

    long countByStatusAndSubmittedAtGreaterThanEqual(TicketStatus status, Instant since);


    @Query("select new com.dataentry.dto.DashboardDtos$DepartmentCount(t.department.id, count(t)) " +
            "from Ticket t group by t.department.id")
    List<DashboardDtos.DepartmentCount> countByDepartment();

    @Query("select new com.dataentry.dto.DashboardDtos$SubcategoryCount(t.subcategory.id, count(t)) " +
            "from Ticket t where t.department.id = :departmentId group by t.subcategory.id")
    List<DashboardDtos.SubcategoryCount> countBySubcategoryForDepartment(@Param("departmentId") Long departmentId);

    @Query("select new com.dataentry.dto.DashboardDtos$DepartmentStatusCount(t.department.id, t.status, count(t)) " +
            "from Ticket t group by t.department.id, t.status")
    List<DashboardDtos.DepartmentStatusCount> countByDepartmentAndStatus();

    @Query("select new com.dataentry.dto.DashboardDtos$SubcategoryStatusCount(t.subcategory.id, t.status, count(t)) " +
            "from Ticket t where t.department.id = :departmentId group by t.subcategory.id, t.status")
    List<DashboardDtos.SubcategoryStatusCount> countBySubcategoryAndStatusForDepartment(@Param("departmentId") Long departmentId);

    @Query("select new com.dataentry.dto.DashboardDtos$DepartmentCount(t.department.id, count(distinct t.submittedBy.id)) " +
            "from Ticket t group by t.department.id")
    List<DashboardDtos.DepartmentCount> distinctAgentsByDepartment();

    @Query("select new com.dataentry.dto.DashboardDtos$DepartmentSubmission(t.department.id, t.submittedAt) " +
            "from Ticket t where t.submittedAt >= :since")
    List<DashboardDtos.DepartmentSubmission> departmentSubmissionsSince(@Param("since") Instant since);

    @Query("select new com.dataentry.dto.DashboardDtos$SubcategorySubmission(t.subcategory.id, t.submittedAt) " +
            "from Ticket t where t.department.id = :departmentId and t.submittedAt >= :since")
    List<DashboardDtos.SubcategorySubmission> subcategorySubmissionsSinceForDepartment(
            @Param("departmentId") Long departmentId,
            @Param("since") Instant since);

    @Query("select new com.dataentry.dto.DashboardDtos$LeaderboardRowRaw(t.submittedBy.id, t.submittedBy.displayName, t.submittedBy.username, count(t)) " +
            "from Ticket t where t.submittedAt >= :since " +
            "group by t.submittedBy.id, t.submittedBy.displayName, t.submittedBy.username " +
            "order by count(t) desc")
    List<DashboardDtos.LeaderboardRowRaw> leaderboardSince(@Param("since") Instant since);

    @Query("select new com.dataentry.dto.DashboardDtos$UserCount(t.submittedBy.id, count(t)) " +
            "from Ticket t where t.submittedAt >= :since group by t.submittedBy.id")
    List<DashboardDtos.UserCount> countByUserSince(@Param("since") Instant since);

    @Query("select t.submittedAt from Ticket t " +
            "where t.submittedBy.id = :userId and t.submittedAt >= :since " +
            "order by t.submittedAt")
    List<Instant> userSubmissionTimesSince(@Param("userId") Long userId, @Param("since") Instant since);

    @Query("select new com.dataentry.dto.DashboardDtos$UserBreakdownRaw(t.department.id, t.department.name, count(t)) " +
            "from Ticket t where t.submittedBy.id = :userId " +
            "group by t.department.id, t.department.name")
    List<DashboardDtos.UserBreakdownRaw> userTicketsByDepartment(@Param("userId") Long userId);

    @Query("select new com.dataentry.dto.DashboardDtos$UserBreakdownRaw(t.subcategory.id, t.subcategory.name, count(t)) " +
            "from Ticket t where t.submittedBy.id = :userId " +
            "group by t.subcategory.id, t.subcategory.name")
    List<DashboardDtos.UserBreakdownRaw> userTicketsBySubcategory(@Param("userId") Long userId);

    @Query("select new com.dataentry.dto.DashboardDtos$StatusCount(t.status, count(t)) " +
            "from Ticket t where t.submittedBy.id = :userId group by t.status")
    List<DashboardDtos.StatusCount> userTicketsByStatus(@Param("userId") Long userId);

    @Query("select count(distinct t.submittedBy.id) from Ticket t where t.submittedAt >= :since")
    long distinctAgentsSince(@Param("since") Instant since);


    @Query("select t.submittedAt from Ticket t where t.submittedAt >= :since order by t.submittedAt")
    List<Instant> submissionTimesSince(@Param("since") Instant since);


    @Query("select new com.dataentry.dto.DashboardDtos$TopPerformer(" +
            "t.submittedBy.id, t.submittedBy.username, " +
            "coalesce(t.submittedBy.displayName, t.submittedBy.username), " +
            "count(t)) " +
            "from Ticket t where t.status = :status " +
            "group by t.submittedBy.id, t.submittedBy.username, t.submittedBy.displayName " +
            "order by count(t) desc")
    List<DashboardDtos.TopPerformer> topPerformersByStatus(@Param("status") TicketStatus status, Pageable pageable);

    // ─── Recycle bin ─────────────────────────────────────────────────────────────
    // Soft-deleted rows are invisible to everything above (entity-level @Where);
    // these native queries deliberately bypass it to list / inspect / restore / purge
    // binned rows. Team scope follows the stats-query convention: null teamId means
    // "no tenant restriction" (super admin views / the retention sweeper).

    interface DeletedTicketRow {
        Long getId();
        String getTitle();
        String getTitleEn();
        String getTitleAr();
        String getStatus();
        Long getSubmittedById();
        String getSubmittedByUsername();
        Instant getDeletedAt();
        Long getDeletedById();
        String getDeletedByName();
        Long getTeamId();
    }

    @Query(value = """
            SELECT t.id AS "id",
                   COALESCE(t.title_en, t.title_ar, t.title) AS "title",
                   t.title_en AS "titleEn",
                   t.title_ar AS "titleAr",
                   t.status AS "status",
                   t.submitted_by_id AS "submittedById",
                   u.username AS "submittedByUsername",
                   t.deleted_at AS "deletedAt",
                   t.deleted_by_id AS "deletedById",
                   COALESCE(db.display_name, db.username) AS "deletedByName",
                   t.team_id AS "teamId"
              FROM tickets t
              LEFT JOIN users u ON u.id = t.submitted_by_id
              LEFT JOIN users db ON db.id = t.deleted_by_id
             WHERE t.deleted_at IS NOT NULL
               AND (CAST(:teamId AS BIGINT) IS NULL OR t.team_id = :teamId)
               AND (CAST(:userId AS BIGINT) IS NULL OR t.submitted_by_id = :userId)
             ORDER BY t.deleted_at DESC
             LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<DeletedTicketRow> findDeletedTickets(@Param("teamId") Long teamId,
                                              @Param("userId") Long userId,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);

    @Query(value = """
            SELECT t.id AS "id",
                   COALESCE(t.title_en, t.title_ar, t.title) AS "title",
                   t.title_en AS "titleEn",
                   t.title_ar AS "titleAr",
                   t.status AS "status",
                   t.submitted_by_id AS "submittedById",
                   u.username AS "submittedByUsername",
                   t.deleted_at AS "deletedAt",
                   t.deleted_by_id AS "deletedById",
                   COALESCE(db.display_name, db.username) AS "deletedByName",
                   t.team_id AS "teamId"
              FROM tickets t
              LEFT JOIN users u ON u.id = t.submitted_by_id
              LEFT JOIN users db ON db.id = t.deleted_by_id
             WHERE t.id = :id AND t.deleted_at IS NOT NULL
            """, nativeQuery = true)
    Optional<DeletedTicketRow> findDeletedTicketById(@Param("id") Long id);

    @Query(value = """
            SELECT COUNT(*) FROM tickets t
             WHERE t.deleted_at IS NOT NULL
               AND (CAST(:teamId AS BIGINT) IS NULL OR t.team_id = :teamId)
               AND (CAST(:userId AS BIGINT) IS NULL OR t.submitted_by_id = :userId)
            """, nativeQuery = true)
    long countDeletedTickets(@Param("teamId") Long teamId, @Param("userId") Long userId);

    /** Team of a ticket, binned or not — native so @Where can't hide the row. */
    @Query(value = "select team_id from tickets where id = :id", nativeQuery = true)
    Long teamIdOfAnyTicket(@Param("id") Long id);

    /** 1 when the ticket is actually sitting in the bin, 0 otherwise. */
    @Query(value = "select count(*) from tickets where id = :id and deleted_at is not null", nativeQuery = true)
    long countBinnedTicketById(@Param("id") Long id);

    @Modifying
    @Query(value = "update tickets set deleted_at = null, deleted_by_id = null "
            + "where id = :id and deleted_at is not null", nativeQuery = true)
    int restoreTicket(@Param("id") Long id);

    @Modifying
    @Query(value = "delete from tickets where id = :id and deleted_at is not null", nativeQuery = true)
    int purgeTicketRow(@Param("id") Long id);

    @Modifying
    @Query(value = "delete from ticket_field_values where ticket_id = :ticketId", nativeQuery = true)
    int purgeTicketFieldValues(@Param("ticketId") Long ticketId);

    @Modifying
    @Query(value = "delete from ticket_resources where ticket_id = :ticketId", nativeQuery = true)
    int purgeTicketResources(@Param("ticketId") Long ticketId);

    @Modifying
    @Query(value = "delete from ticket_documents where ticket_id = :ticketId", nativeQuery = true)
    int purgeTicketDocuments(@Param("ticketId") Long ticketId);

    /** Binned tickets past the retention window — the sweeper's worklist. */
    @Query(value = "select id from tickets where deleted_at is not null and deleted_at < :cutoff", nativeQuery = true)
    List<Long> findExpiredDeletedTicketIds(@Param("cutoff") Instant cutoff);

    /**
     * Binned tickets still referencing a department/subcategory block that structural
     * row from being hard-deleted (FK). Deleting the structure is refused until the bin
     * entries are restored or purged, so the bin's promise is never silently broken.
     */
    @Query(value = "select count(*) from tickets where department_id = :departmentId and deleted_at is not null", nativeQuery = true)
    long countDeletedTicketsByDepartmentId(@Param("departmentId") Long departmentId);

    @Query(value = "select count(*) from tickets where subcategory_id = :subcategoryId and deleted_at is not null", nativeQuery = true)
    long countDeletedTicketsBySubcategoryId(@Param("subcategoryId") Long subcategoryId);

    /**
     * Live entries attached to a project — either directly or through one of its
     * departments. A project purge is refused while any remain.
     */
    @Query(value = """
            SELECT COUNT(*) FROM tickets t
             WHERE t.deleted_at IS NULL
               AND (t.project_id = :projectId
                    OR t.department_id IN (SELECT d.id FROM departments d WHERE d.project_id = :projectId))
            """, nativeQuery = true)
    long countLiveTicketsAttachedToProject(@Param("projectId") Long projectId);

    @Query(value = """
            SELECT t.id FROM tickets t
             WHERE t.deleted_at IS NOT NULL
               AND (t.project_id = :projectId
                    OR t.department_id IN (SELECT d.id FROM departments d WHERE d.project_id = :projectId))
            """, nativeQuery = true)
    List<Long> findDeletedTicketIdsAttachedToProject(@Param("projectId") Long projectId);

    @Modifying
    @Query(value = """
            DELETE FROM tickets
             WHERE deleted_at IS NOT NULL
               AND (project_id = :projectId
                    OR department_id IN (SELECT d.id FROM departments d WHERE d.project_id = :projectId))
            """, nativeQuery = true)
    int purgeDeletedTicketsAttachedToProject(@Param("projectId") Long projectId);

    // ─── Global search (Ctrl+K palette) ────────────────────────────────────────
    // Bounded results only — a palette, not a report. Native + explicit team scope
    // for parity with the stats queries; deleted rows filtered everywhere.

    interface SearchTicketRow {
        Long getId();
        String getTitle();
        String getTitleEn();
        String getTitleAr();
        String getStatus();
        Long getSubmittedById();
        String getSubmittedByUsername();
        Instant getSubmittedAt();
    }

    @Query(value = """
            SELECT t.id AS "id",
                   COALESCE(t.title_en, t.title_ar, t.title) AS "title",
                   t.title_en AS "titleEn",
                   t.title_ar AS "titleAr",
                   t.status AS "status",
                   t.submitted_by_id AS "submittedById",
                   u.username AS "submittedByUsername",
                   t.submitted_at AS "submittedAt"
              FROM tickets t
              LEFT JOIN users u ON u.id = t.submitted_by_id
             WHERE t.deleted_at IS NULL
               AND (CAST(:teamId AS BIGINT) IS NULL OR t.team_id = :teamId)
               AND (lower(t.title) LIKE :pattern
                    OR lower(t.title_en) LIKE :pattern
                    OR lower(t.title_ar) LIKE :pattern)
             ORDER BY t.submitted_at DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<SearchTicketRow> searchTickets(@Param("teamId") Long teamId,
                                        @Param("pattern") String pattern,
                                        @Param("limit") int limit);

    // ─── Per-agent quality / workload aggregates (C3 + C5) ─────────────────────

    interface UserStatusCountRow {
        Long getUserId();
        String getStatus();
        long getTotal();
    }

    @Query(value = """
            SELECT t.submitted_by_id AS "userId", t.status AS "status", COUNT(*) AS "total"
              FROM tickets t
             WHERE t.deleted_at IS NULL
               AND (CAST(:teamId AS BIGINT) IS NULL OR t.team_id = :teamId)
             GROUP BY 1, 2
            """, nativeQuery = true)
    List<UserStatusCountRow> statusCountsByUser(@Param("teamId") Long teamId);

    @Query(value = """
            SELECT t.submitted_by_id AS "userId", t.status AS "status", COUNT(*) AS "total"
              FROM tickets t
             WHERE t.deleted_at IS NULL
               AND (CAST(:teamId AS BIGINT) IS NULL OR t.team_id = :teamId)
               AND t.submitted_at >= :since
             GROUP BY 1, 2
            """, nativeQuery = true)
    List<UserStatusCountRow> statusCountsByUserSince(@Param("teamId") Long teamId,
                                                     @Param("since") Instant since);
}
