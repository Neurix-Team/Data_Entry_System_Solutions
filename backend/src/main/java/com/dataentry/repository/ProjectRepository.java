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

    /** All projects a specific user is a member of (ordered by creation, newest first). */
    @EntityGraph(attributePaths = {"members", "department"})
    @org.springframework.data.jpa.repository.Query(
            "select p from Project p join p.members m where m.id = :userId order by p.createdAt desc")
    List<Project> findAllByMemberId(@org.springframework.data.repository.query.Param("userId") Long userId);
}
