package com.dataentry.repository;

import com.dataentry.model.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DepartmentRepository extends JpaRepository<Department, Long> {
    List<Department> findAllByActiveTrueOrderByNameAsc();
    List<Department> findAllByActiveTrueAndProjectIdOrderByNameAsc(Long projectId);
    List<Department> findAllByActiveTrueAndProjectIdInOrderByNameAsc(java.util.Collection<Long> projectIds);
    List<Department> findAllByProjectId(Long projectId);
    /** Batch variant used to preload every project's departments in one round-trip. */
    List<Department> findAllByProjectIdIn(java.util.Collection<Long> projectIds);
    boolean existsByNameIgnoreCase(String name);
}
