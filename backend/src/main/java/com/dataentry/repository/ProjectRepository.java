package com.dataentry.repository;

import com.dataentry.model.Project;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    @EntityGraph(attributePaths = {"members", "department"})
    List<Project> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"members", "department"})
    Optional<Project> findWithMembersById(Long id);
}
