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

    @EntityGraph(attributePaths = {"customValues", "customValues.field", "department", "subcategory", "submittedBy"})
    Page<Ticket> findAllBySubmittedByOrderBySubmittedAtDesc(User submittedBy, Pageable pageable);

    @EntityGraph(attributePaths = {"customValues", "customValues.field", "department", "subcategory", "submittedBy"})
    Page<Ticket> findAllByOrderBySubmittedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = {"customValues", "customValues.field", "department", "subcategory", "submittedBy"})
    Optional<Ticket> findWithDetailsById(Long id);

    long countBySubmittedBy(User submittedBy);

    long countBySubcategoryId(Long subcategoryId);

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
