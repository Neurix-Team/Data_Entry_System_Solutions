package com.dataentry.repository;

import com.dataentry.model.ChatGroupMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ChatGroupMessageRepository extends JpaRepository<ChatGroupMessage, Long> {

    List<ChatGroupMessage> findByGroupIdAndIdGreaterThanOrderByIdAsc(Long groupId, Long afterId);

    @Query("""
            select m from ChatGroupMessage m
            where m.group.id = :groupId
            order by m.id desc
            """)
    List<ChatGroupMessage> findRecent(@Param("groupId") Long groupId, Pageable pageable);

    /**
     * Unread = sent by someone else, after the member's own last-read watermark. The
     * caller passes {@code Instant.EPOCH} for a member who has never marked anything
     * read, so every message of theirs counts — keeping the "never read" case out of
     * this query instead of relying on portable-across-dialects timestamp arithmetic.
     */
    @Query("""
            select count(m) from ChatGroupMessage m
            where m.group.id = :groupId
              and (m.sender.id is null or m.sender.id <> :userId)
              and m.createdAt > :lastReadAt
            """)
    long countUnread(@Param("groupId") Long groupId, @Param("userId") Long userId,
                     @Param("lastReadAt") Instant lastReadAt);
}
