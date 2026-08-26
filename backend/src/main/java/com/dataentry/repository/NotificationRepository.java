package com.dataentry.repository;

import com.dataentry.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** Newest first — the notifications panel shows a chronological feed. */
    List<Notification> findAllByRecipientIdOrderByCreatedAtDesc(Long recipientId);

    long countByRecipientIdAndReadAtIsNull(Long recipientId);

    /** Ownership-scoped fetch so a caller can only ever mark their own notifications read. */
    Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.recipient.id = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);
}
