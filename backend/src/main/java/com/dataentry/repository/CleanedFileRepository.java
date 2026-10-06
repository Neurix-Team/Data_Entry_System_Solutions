package com.dataentry.repository;

import com.dataentry.model.CleanedFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CleanedFileRepository extends JpaRepository<CleanedFile, Long>, JpaSpecificationExecutor<CleanedFile> {
    @Override
    @EntityGraph(attributePaths = {"project", "project.team", "department"})
    Page<CleanedFile> findAll(Specification<CleanedFile> spec, Pageable pageable);
}
