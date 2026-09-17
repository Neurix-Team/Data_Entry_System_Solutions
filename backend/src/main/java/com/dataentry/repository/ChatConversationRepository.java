package com.dataentry.repository;

import com.dataentry.model.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {

    Optional<ChatConversation> findByUserAIdAndUserBId(Long userAId, Long userBId);

    @Query("""
            select c from ChatConversation c
            where c.userA.id = :userId or c.userB.id = :userId
            order by coalesce(c.lastMessageAt, c.createdAt) desc
            """)
    List<ChatConversation> findAllForUser(@Param("userId") Long userId);

    @Query("""
            select count(c) from ChatConversation c
            where c.userA.id = :userId or c.userB.id = :userId
            """)
    long countForUser(@Param("userId") Long userId);
}
