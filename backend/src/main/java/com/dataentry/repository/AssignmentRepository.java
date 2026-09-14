package com.dataentry.repository;

import com.dataentry.model.Assignment;
import com.dataentry.model.AssignmentStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    @EntityGraph(attributePaths = {"assignee", "assignedBy"})
    @Query("select a from Assignment a order by a.createdAt desc, a.id desc")
    List<Assignment> findAllForTeam();

    @EntityGraph(attributePaths = {"assignee", "assignedBy"})
    @Query("select a from Assignment a where a.assignee.id = :userId order by a.createdAt desc, a.id desc")
    List<Assignment> findAllForAssignee(@Param("userId") Long userId);

    @EntityGraph(attributePaths = {"assignee", "assignedBy"})
    Optional<Assignment> findWithPeopleById(Long id);

    long countByStatus(AssignmentStatus status);

    long countByAssigneeIdAndStatus(Long assigneeId, AssignmentStatus status);
}
