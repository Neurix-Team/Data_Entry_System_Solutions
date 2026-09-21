package com.dataentry.repository;

import com.dataentry.model.ChatGroupAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ChatGroupAttachmentRepository extends JpaRepository<ChatGroupAttachment, Long> {

    List<ChatGroupAttachment> findByMessageIdOrderByIdAsc(Long messageId);

    List<ChatGroupAttachment> findByMessageIdInOrderByIdAsc(Collection<Long> messageIds);
}
