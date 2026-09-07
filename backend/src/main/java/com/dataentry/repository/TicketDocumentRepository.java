package com.dataentry.repository;

import com.dataentry.model.TicketDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TicketDocumentRepository extends JpaRepository<TicketDocument, Long> {
    Optional<TicketDocument> findByIdAndTicketId(Long id, Long ticketId);
    List<TicketDocument> findAllByTicketIdOrderByUploadedAtAscIdAsc(Long ticketId);

    @Query("SELECT d FROM TicketDocument d " +
           "WHERE d.contentHash = :hash AND d.ticket.project.id = :projectId " +
           "ORDER BY d.uploadedAt ASC, d.id ASC")
    List<TicketDocument> findByProjectAndHash(@Param("projectId") Long projectId,
                                              @Param("hash") String hash);

    @Query("SELECT d FROM TicketDocument d " +
           "WHERE d.contentHash = :hash " +
           "  AND d.ticket.project IS NULL " +
           "  AND d.ticket.team.id = :teamId " +
           "ORDER BY d.uploadedAt ASC, d.id ASC")
    List<TicketDocument> findByTeamAndHashWithoutProject(@Param("teamId") Long teamId,
                                                          @Param("hash") String hash);
}
