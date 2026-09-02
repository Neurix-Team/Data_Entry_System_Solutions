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

    /**
     * First existing document with the same SHA-256 anywhere inside the given project.
     * The upload path uses this to reject byte-identical duplicates regardless of what
     * the user renamed the file to. Ordered by upload time so the oldest copy — the one
     * the "already exists" message points at — is stable across retries.
     */
    @Query("SELECT d FROM TicketDocument d " +
           "WHERE d.contentHash = :hash AND d.ticket.project.id = :projectId " +
           "ORDER BY d.uploadedAt ASC, d.id ASC")
    List<TicketDocument> findByProjectAndHash(@Param("projectId") Long projectId,
                                              @Param("hash") String hash);

    /**
     * Fallback for the (rare) legacy case where a ticket has no project — dedup falls
     * back to the owning team's scope so cross-workspace uploads still work but a solo
     * duplicate inside the same admin's workspace is still caught.
     */
    @Query("SELECT d FROM TicketDocument d " +
           "WHERE d.contentHash = :hash " +
           "  AND d.ticket.project IS NULL " +
           "  AND d.ticket.team.id = :teamId " +
           "ORDER BY d.uploadedAt ASC, d.id ASC")
    List<TicketDocument> findByTeamAndHashWithoutProject(@Param("teamId") Long teamId,
                                                          @Param("hash") String hash);
}
