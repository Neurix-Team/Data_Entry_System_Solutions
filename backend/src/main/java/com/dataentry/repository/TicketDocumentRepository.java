package com.dataentry.repository;

import com.dataentry.model.TicketDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TicketDocumentRepository extends JpaRepository<TicketDocument, Long> {
    Optional<TicketDocument> findByIdAndTicketId(Long id, Long ticketId);
    List<TicketDocument> findAllByTicketIdOrderByUploadedAtAscIdAsc(Long ticketId);
}
