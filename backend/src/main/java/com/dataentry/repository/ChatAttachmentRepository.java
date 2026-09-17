package com.dataentry.repository;

import com.dataentry.model.ChatAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, Long> {

    List<ChatAttachment> findByMessageIdOrderByIdAsc(Long messageId);

    List<ChatAttachment> findByMessageIdInOrderByIdAsc(java.util.Collection<Long> messageIds);

    Optional<ChatAttachment> findById(Long id);
}
