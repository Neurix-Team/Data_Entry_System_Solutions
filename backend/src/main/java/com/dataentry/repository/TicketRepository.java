package com.dataentry.repository;

import com.dataentry.model.Ticket;
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

    // ----- aggregation queries (dashboards) -----

    @Query("select t.department.id, count(t) from Ticket t group by t.department.id")
    List<Object[]> countByDepartment();

    @Query("select t.subcategory.id, count(t) from Ticket t where t.department.id = :departmentId group by t.subcategory.id")
    List<Object[]> countBySubcategoryForDepartment(@Param("departmentId") Long departmentId);

    @Query("select t.department.id, t.status, count(t) from Ticket t group by t.department.id, t.status")
    List<Object[]> countByDepartmentAndStatus();

    @Query("select t.subcategory.id, t.status, count(t) from Ticket t where t.department.id = :departmentId group by t.subcategory.id, t.status")
    List<Object[]> countBySubcategoryAndStatusForDepartment(@Param("departmentId") Long departmentId);

    @Query("select t.department.id, count(distinct t.submittedBy.id) from Ticket t group by t.department.id")
    List<Object[]> distinctAgentsByDepartment();

    @Query("select t.department.id, t.submittedAt from Ticket t where t.submittedAt >= :since")
    List<Object[]> departmentSubmissionsSince(@Param("since") Instant since);

    @Query("select t.subcategory.id, t.submittedAt from Ticket t where t.department.id = :departmentId and t.submittedAt >= :since")
    List<Object[]> subcategorySubmissionsSinceForDepartment(
            @Param("departmentId") Long departmentId,
            @Param("since") Instant since);

    @Query("select t.submittedBy.id, t.submittedBy.displayName, t.submittedBy.username, count(t) " +
            "from Ticket t where t.submittedAt >= :since " +
            "group by t.submittedBy.id, t.submittedBy.displayName, t.submittedBy.username order by count(t) desc")
    List<Object[]> leaderboardSince(@Param("since") Instant since);

    @Query("select t.submittedBy.id, count(t) from Ticket t where t.submittedAt >= :since group by t.submittedBy.id")
    List<Object[]> countByUserSince(@Param("since") Instant since);

    @Query("select t.submittedAt from Ticket t where t.submittedBy.id = :userId and t.submittedAt >= :since order by t.submittedAt")
    List<Instant> userSubmissionTimesSince(@Param("userId") Long userId, @Param("since") Instant since);

    @Query("select t.department.id, t.department.name, count(t) from Ticket t " +
            "where t.submittedBy.id = :userId group by t.department.id, t.department.name")
    List<Object[]> userTicketsByDepartment(@Param("userId") Long userId);

    @Query("select t.subcategory.id, t.subcategory.name, count(t) from Ticket t " +
            "where t.submittedBy.id = :userId group by t.subcategory.id, t.subcategory.name")
    List<Object[]> userTicketsBySubcategory(@Param("userId") Long userId);

    @Query("select t.status, count(t) from Ticket t where t.submittedBy.id = :userId group by t.status")
    List<Object[]> userTicketsByStatus(@Param("userId") Long userId);

    @Query("select count(distinct t.submittedBy.id) from Ticket t where t.submittedAt >= :since")
    long distinctAgentsSince(@Param("since") Instant since);
}
