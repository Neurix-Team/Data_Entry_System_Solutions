package com.dataentry.repository;

import com.dataentry.model.Subcategory;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubcategoryRepository extends JpaRepository<Subcategory, Long> {

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
}
