package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A flat, durable export snapshot. The source ticket id is unique, so publishing is an
 * upsert rather than an append operation and cannot duplicate the same logical record.
 */
@Entity
@Table(name = "dataset_records", indexes = {
        @Index(name = "idx_dataset_source_ticket", columnList = "source_ticket_id", unique = true),
        @Index(name = "idx_dataset_published_at", columnList = "published_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DatasetRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_ticket_id", nullable = false, unique = true)
    private Long sourceTicketId;

    private Long teamId;
    @Column(length = 250) private String teamName;
    private Long projectId;
    @Column(length = 500) private String projectName;
    private Long departmentId;
    @Column(length = 500) private String departmentName;
    private Long subcategoryId;
    @Column(length = 500) private String subcategoryName;

    private Long submittedByUserId;
    @Column(length = 150) private String submittedByUsername;
    @Column(length = 250) private String submittedByDisplayName;
    @Column(length = 320) private String submittedByEmail;
    @Column(length = 80) private String submittedByPhone;
    @Column(length = 40) private String submittedByRole;

    @Column(length = 500) private String title;
    @Column(columnDefinition = "TEXT") private String content;
    @Column(length = 250) private String websiteName;
    @Column(length = 1000) private String websiteLink;
    @Column(length = 40) private String status;
    private Instant submittedAt;

    @Column(name = "attachments_json", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String attachmentsJson = "[]";

    /** Number of physical attachments represented by this snapshot. Nullable only for
     * rows created before this counter was introduced; the next publish backfills it. */
    @Column(name = "attachment_count")
    private Integer attachmentCount;

    @Column(name = "custom_fields_json", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String customFieldsJson = "[]";

    @Column(name = "source_fingerprint", nullable = false, length = 64)
    private String sourceFingerprint;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;

    @Column(name = "refreshed_at", nullable = false)
    private Instant refreshedAt;
}
