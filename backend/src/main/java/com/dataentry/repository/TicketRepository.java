package com.dataentry.repository;

import com.dataentry.dto.DashboardDtos;
import com.dataentry.model.Ticket;
import com.dataentry.model.TicketStatus;
import com.dataentry.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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

    /**
     * All admin KPI counters in one network round-trip.
     *
     * <p>{@code teamId} may be {@code null} — that's the SUPER_ADMIN case (no team entered yet),
     * where the pre-refactor code returned unscoped totals across every team. The
     * {@code CAST(:teamId AS BIGINT) IS NULL OR ...} predicate reproduces that behavior without
     * duplicating the query. The cast is required because PostgreSQL cannot infer the parameter
     * type from a bare {@code IS NULL} check.
     */
    @Query(value = """
            SELECT
              (SELECT COUNT(*) FROM tickets
                WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId)) AS "totalTickets",
              (SELECT COUNT(*) FROM departments
                WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId)) AS "totalDepartments",
              (SELECT COUNT(*) FROM custom_fields
                WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId) AND active = TRUE)
                AS "activeFields",
              (SELECT COUNT(*) FROM users
                WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId)) AS "totalUsers",
              (SELECT COUNT(*) FROM tickets
                WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId) AND status = 'IN_PROGRESS')
                AS "inProgress",
              (SELECT COUNT(*) FROM tickets
                WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId) AND status = 'REVIEW')
                AS "review",
              (SELECT COUNT(*) FROM tickets
                WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId) AND status = 'COMPLETED')
                AS "completed",
              (SELECT COUNT(*) FROM tickets
                WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId)
                  AND status = 'COMPLETED' AND submitted_at >= :startOfToday)
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

    // ----- Project Folders view (grouped-by-project ticket lists) -----
    //
    // The entity graph deliberately fetches only ONE collection (customValues). Ticket has
    // three ToMany collections (customValues, resources, documents) — asking Hibernate to
    // fetch more than one in a single query throws MultipleBagFetchException, which was
    // showing up as an opaque "Unexpected server error" on the folder page. The remaining
    // resources/documents collections lazy-load per row inside the @Transactional service
    // method; the extra queries are bounded by the folder size and preferable to the crash.

    @EntityGraph(attributePaths = {"customValues", "customValues.field", "department", "subcategory", "project", "submittedBy"})
    List<Ticket> findAllByProjectIdOrderBySubmittedAtDesc(Long projectId);

    @EntityGraph(attributePaths = {"customValues", "customValues.field", "department", "subcategory", "project", "submittedBy"})
    List<Ticket> findAllByProjectIdAndSubmittedByIdOrderBySubmittedAtDesc(Long projectId, Long userId);

    long countByProjectId(Long projectId);

    long countByProjectIdAndStatus(Long projectId, TicketStatus status);

    long countByProjectIdAndSubmittedById(Long projectId, Long userId);

    long countByProjectIdAndSubmittedByIdAndStatus(Long projectId, Long userId, TicketStatus status);

    // ----- derived-name aggregations (used by AdminStats) -----

    long countByStatus(TicketStatus status);

    long countByStatusAndSubmittedAtGreaterThanEqual(TicketStatus status, Instant since);

    // ----- typed JPQL aggregations (dashboards) -----

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

    // ----- for daily bucketing on report (byDay) -----

    @Query("select t.submittedAt from Ticket t where t.submittedAt >= :since order by t.submittedAt")
    List<Instant> submissionTimesSince(@Param("since") Instant since);

    // ----- top performers by status (used by /admin/reports) -----

    @Query("select new com.dataentry.dto.DashboardDtos$TopPerformer(" +
            "t.submittedBy.id, t.submittedBy.username, " +
            "coalesce(t.submittedBy.displayName, t.submittedBy.username), " +
            "count(t)) " +
            "from Ticket t where t.status = :status " +
            "group by t.submittedBy.id, t.submittedBy.username, t.submittedBy.displayName " +
            "order by count(t) desc")
    List<DashboardDtos.TopPerformer> topPerformersByStatus(@Param("status") TicketStatus status, Pageable pageable);
}
