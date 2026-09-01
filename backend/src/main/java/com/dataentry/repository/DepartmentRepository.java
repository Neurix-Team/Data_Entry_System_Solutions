package com.dataentry.repository;

import com.dataentry.model.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DepartmentRepository extends JpaRepository<Department, Long> {
    interface DomainAggregateRow {
        Long getDepartmentId();
        String getName();
        String getNameEn();
        String getNameAr();
        long getTotalTickets();
        long getSubcategoryCount();
        long getActiveAgents();
        long getInProgress();
        long getReview();
        long getCompleted();
    }

    /**
     * Dashboard domain cards in one aggregate query instead of per-department counters.
     *
     * <p>{@code teamId} may be {@code null} for a SUPER_ADMIN session with no team entered;
     * in that case aggregates run across every team, matching the pre-refactor behavior.
     */
    @Query(value = """
            SELECT d.id AS "departmentId",
                   d.name AS "name",
                   d.name_en AS "nameEn",
                   d.name_ar AS "nameAr",
                   COUNT(DISTINCT s.id) AS "subcategoryCount",
                   COUNT(DISTINCT t.id) AS "totalTickets",
                   COUNT(DISTINCT t.submitted_by_id) AS "activeAgents",
                   COUNT(DISTINCT t.id) FILTER (WHERE t.status = 'IN_PROGRESS') AS "inProgress",
                   COUNT(DISTINCT t.id) FILTER (WHERE t.status = 'REVIEW') AS "review",
                   COUNT(DISTINCT t.id) FILTER (WHERE t.status = 'COMPLETED') AS "completed"
              FROM departments d
              LEFT JOIN subcategories s
                     ON s.department_id = d.id
                    AND (CAST(:teamId AS BIGINT) IS NULL OR s.team_id = :teamId)
              LEFT JOIN tickets t
                     ON t.department_id = d.id
                    AND (CAST(:teamId AS BIGINT) IS NULL OR t.team_id = :teamId)
             WHERE (CAST(:teamId AS BIGINT) IS NULL OR d.team_id = :teamId)
             GROUP BY d.id, d.name, d.name_en, d.name_ar
             ORDER BY LOWER(d.name), d.id
            """, nativeQuery = true)
    List<DomainAggregateRow> findDomainAggregates(@Param("teamId") Long teamId);

    List<Department> findAllByActiveTrueOrderByNameAsc();
    List<Department> findAllByActiveTrueAndProjectIdOrderByNameAsc(Long projectId);
    List<Department> findAllByActiveTrueAndProjectIdInOrderByNameAsc(java.util.Collection<Long> projectIds);
    List<Department> findAllByProjectId(Long projectId);
    /** Batch variant used to preload every project's departments in one round-trip. */
    List<Department> findAllByProjectIdIn(java.util.Collection<Long> projectIds);
    boolean existsByNameIgnoreCase(String name);
}
