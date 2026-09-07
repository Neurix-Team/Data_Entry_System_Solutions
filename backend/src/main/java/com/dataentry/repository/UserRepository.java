package com.dataentry.repository;

import com.dataentry.model.Role;
import com.dataentry.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);

    List<User> findAllByRole(Role role);

    List<User> findAllByTeamIdOrderByCreatedAtDesc(Long teamId);

    boolean existsByTeamIdAndRole(Long teamId, Role role);

    List<User> findAllByTeamIdAndRoleOrderByCreatedAtAsc(Long teamId, Role role);

    @org.springframework.data.jpa.repository.Query(
            "select u from User u join u.team t "
                    + "where t.id = :teamId "
                    + "and u.id in (select m.id from Project p join p.members m where p.id = :projectId)")
    List<User> findMembersOfProjectInTeam(
            @org.springframework.data.repository.query.Param("projectId") Long projectId,
            @org.springframework.data.repository.query.Param("teamId") Long teamId);

    @org.springframework.data.jpa.repository.Query(
            "select p.id as projectId, u "
                    + "from Project p join p.members u "
                    + "where p.id in :projectIds and u.team.id = :teamId")
    List<Object[]> findMembersOfProjectsInTeam(
            @org.springframework.data.repository.query.Param("projectIds") java.util.Collection<Long> projectIds,
            @org.springframework.data.repository.query.Param("teamId") Long teamId);
}
