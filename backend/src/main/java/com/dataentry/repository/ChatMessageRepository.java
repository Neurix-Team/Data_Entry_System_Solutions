package com.dataentry.repository;

import com.dataentry.model.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByConversationIdOrderByIdAsc(Long conversationId);

    List<ChatMessage> findByConversationIdAndIdGreaterThanOrderByIdAsc(Long conversationId, Long afterId);

    @Query("""
            select m from ChatMessage m
            where m.conversation.id = :conversationId
            order by m.id desc
            """)
    List<ChatMessage> findRecent(@Param("conversationId") Long conversationId, Pageable pageable);

    @Modifying
    @Query("""
            update ChatMessage m set m.readAt = :now
            where m.conversation.id = :conversationId
              and m.sender.id <> :readerId
              and m.readAt is null
            """)
    int markConversationRead(@Param("conversationId") Long conversationId,
                             @Param("readerId") Long readerId,
                             @Param("now") Instant now);

    long countByConversationIdAndSenderIdNotAndReadAtIsNull(Long conversationId, Long senderId);

    @Query("""
            select m.conversation.id as convId, count(m) as unread
            from ChatMessage m
            where (m.conversation.userA.id = :userId or m.conversation.userB.id = :userId)
              and m.sender.id <> :userId
              and m.readAt is null
            group by m.conversation.id
            """)
    List<Object[]> countUnreadByConversation(@Param("userId") Long userId);
}
