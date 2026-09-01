package com.dataentry.repository;

import com.dataentry.model.Subcategory;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SubcategoryRepository extends JpaRepository<Subcategory, Long> {

    interface ListRow {
        Long getId();
        Long getDepartmentId();
        String getDepartmentName();
        String getDepartmentNameEn();
        String getDepartmentNameAr();
        String getName();
        String getNameEn();
        String getNameAr();
        Boolean getActive();
        long getTicketCount();
        long getFieldCount();
    }

    /**
     * One PostgreSQL round-trip for the complete admin list, including both counters.
     * The previous entity mapping executed two COUNT queries per subcategory (N+1).
     *
     * <p>{@code teamId} may be {@code null} for a SUPER_ADMIN session with no team entered;
     * every filter degrades to "no team restriction" in that case so cross-team totals surface,
     * matching the pre-refactor behavior.
     */
    @Query(value = """
            SELECT s.id AS "id",
                   d.id AS "departmentId",
                   d.name AS "departmentName",
                   d.name_en AS "departmentNameEn",
                   d.name_ar AS "departmentNameAr",
                   s.name AS "name",
                   s.name_en AS "nameEn",
                   s.name_ar AS "nameAr",
                   s.active AS "active",
                   COALESCE(tc.total, 0) AS "ticketCount",
                   COALESCE(fc.total, 0) AS "fieldCount"
              FROM subcategories s
              JOIN departments d
                ON d.id = s.department_id
               AND (CAST(:teamId AS BIGINT) IS NULL OR d.team_id = :teamId)
              LEFT JOIN (
                    SELECT subcategory_id, COUNT(*) AS total
                      FROM tickets
                     WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId)
                     GROUP BY subcategory_id
              ) tc ON tc.subcategory_id = s.id
              LEFT JOIN (
                    SELECT subcategory_id, COUNT(*) AS total
                      FROM custom_fields
                     WHERE (CAST(:teamId AS BIGINT) IS NULL OR team_id = :teamId)
                     GROUP BY subcategory_id
              ) fc ON fc.subcategory_id = s.id
             WHERE (CAST(:teamId AS BIGINT) IS NULL OR s.team_id = :teamId)
               AND (CAST(:departmentId AS BIGINT) IS NULL OR s.department_id = :departmentId)
               AND (:activeOnly = FALSE OR s.active = TRUE)
             ORDER BY s.department_id, LOWER(s.name), s.id
            """, nativeQuery = true)
    List<ListRow> findAdminListRows(@Param("teamId") Long teamId,
                                    @Param("departmentId") Long departmentId,
                                    @Param("activeOnly") boolean activeOnly);

    @EntityGraph(attributePaths = "department")
    List<Subcategory> findAllByDepartmentIdOrderByNameAsc(Long departmentId);

    @EntityGraph(attributePaths = "department")
    List<Subcategory> findAllByDepartmentIdAndActiveTrueOrderByNameAsc(Long departmentId);

    @EntityGraph(attributePaths = "department")
    List<Subcategory> findAllByActiveTrueOrderByDepartmentIdAscNameAsc();

    @EntityGraph(attributePaths = "department")
    List<Subcategory> findAllByOrderByDepartmentIdAscNameAsc();

    boolean existsByDepartmentIdAndNameIgnoreCase(Long departmentId, String name);

    long countByDepartmentId(Long departmentId);

    List<Subcategory> findAllByDepartmentId(Long departmentId);
}
