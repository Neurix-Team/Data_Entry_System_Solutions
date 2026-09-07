package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.Instant;

@Entity
@Table(
        name = "upload_sessions",
        indexes = {
                @Index(name = "ix_upload_sessions_owner", columnList = "owner_id"),
                @Index(name = "ix_upload_sessions_expires", columnList = "expires_at")
        }
)
@Filter(name = "teamFilter", condition = "team_id = :teamId")
@EntityListeners(TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadSession implements TeamOwned {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UploadTarget target;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "ticket_id")
    private Long ticketId;

    @Column(length = 250)
    private String title;

    @Column(name = "original_filename", nullable = false, length = 300)
    private String originalFilename;

    @Column(name = "client_content_type", length = 200)
    private String clientContentType;

    @Column(name = "declared_size", nullable = false)
    private long declaredSize;

    @Column(name = "chunk_bytes", nullable = false)
    private int chunkBytes;

    @Column(name = "total_chunks", nullable = false)
    private int totalChunks;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
