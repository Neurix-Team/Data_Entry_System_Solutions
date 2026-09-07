package com.dataentry.repository;

import com.dataentry.model.Project;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {


    List<Project> findAllByOrderByCreatedAtDesc();

    Optional<Project> findWithMembersById(Long id);

    @org.springframework.data.jpa.repository.Query(
            "select p from Project p join p.members m where m.id = :userId order by p.createdAt desc")
    List<Project> findAllByMemberId(@org.springframework.data.repository.query.Param("userId") Long userId);


    @org.springframework.data.jpa.repository.Query(
            "select p from Project p order by p.createdAt desc")
    List<Project> findAllForFolderView();

    @org.springframework.data.jpa.repository.Query(
            "select p from Project p join p.members m where m.id = :userId order by p.createdAt desc")
    List<Project> findMemberProjectsForFolderView(@org.springframework.data.repository.query.Param("userId") Long userId);

    @org.springframework.data.jpa.repository.Query(
            "select count(p) > 0 from Project p join p.members m where p.id = :projectId and m.id = :userId")
    boolean isMember(@org.springframework.data.repository.query.Param("projectId") Long projectId,
                     @org.springframework.data.repository.query.Param("userId") Long userId);
}
