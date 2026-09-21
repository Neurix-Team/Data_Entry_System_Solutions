package com.dataentry.repository;

import com.dataentry.model.ChatGroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatGroupMemberRepository extends JpaRepository<ChatGroupMember, Long> {

    Optional<ChatGroupMember> findByGroupIdAndUserId(Long groupId, Long userId);

    List<ChatGroupMember> findAllByGroupIdOrderByRoleAscJoinedAtAsc(Long groupId);

    List<ChatGroupMember> findAllByUserId(Long userId);

    long countByGroupId(Long groupId);

    long countByGroupIdAndRole(Long groupId, ChatGroupMember.GroupRole role);

    @Query("select m.group.id from ChatGroupMember m where m.user.id = :userId")
    List<Long> findGroupIdsForUser(@Param("userId") Long userId);
}
