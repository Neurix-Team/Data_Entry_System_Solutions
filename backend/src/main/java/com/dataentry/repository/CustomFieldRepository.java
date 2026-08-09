package com.dataentry.repository;

import com.dataentry.model.CustomField;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomFieldRepository extends JpaRepository<CustomField, Long> {

    @EntityGraph(attributePaths = {"subcategory", "subcategory.department"})
    List<CustomField> findAllByOrderByDisplayOrderAscIdAsc();

    @EntityGraph(attributePaths = {"subcategory", "subcategory.department"})
    List<CustomField> findAllBySubcategoryIdOrderByDisplayOrderAscIdAsc(Long subcategoryId);

    @EntityGraph(attributePaths = {"subcategory", "subcategory.department"})
    List<CustomField> findAllBySubcategoryIdAndActiveTrueOrderByDisplayOrderAscIdAsc(Long subcategoryId);

    @EntityGraph(attributePaths = {"subcategory", "subcategory.department"})
    List<CustomField> findAllByActiveTrueOrderByDisplayOrderAscIdAsc();

    boolean existsBySubcategoryIdAndFieldKeyIgnoreCase(Long subcategoryId, String fieldKey);

    long countBySubcategoryId(Long subcategoryId);
}
