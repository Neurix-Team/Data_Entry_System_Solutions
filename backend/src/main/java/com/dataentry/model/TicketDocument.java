package com.dataentry.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** An uploaded file attached to a ticket. Bytes live on disk under app.attachments.dir. */
@Entity
@Table(name = "ticket_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    @JsonIgnore
    private Ticket ticket;

    @Column(nullable = false, length = 250)
    private String name;

    @Column(name = "original_filename", nullable = false, length = 300)
    private String originalFilename;

    @Column(name = "content_type", length = 200)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Path relative to app.attachments.dir — never send this to clients. */
    @Column(name = "storage_path", nullable = false, length = 500)
    private String storagePath;

    /**
     * SHA-256 of the raw file bytes, hex-encoded (64 chars). Used to reject duplicate
     * uploads within a project scope — same bytes → same hash, regardless of what the
     * user renamed the file to. Nullable so historical rows uploaded before this column
     * existed still round-trip; those never participate in duplicate detection until they
     * get re-hashed.
     */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant uploadedAt = Instant.now();
}
