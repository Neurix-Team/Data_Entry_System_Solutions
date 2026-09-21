package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Where;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-entry record. Soft delete: while {@code deletedAt} is set the row sits in the
 * recycle bin — invisible to every Hibernate query (the class-level @Where restricts
 * them all), still fully intact for a restore, and swept permanently only after the
 * retention window. @Where (not @SoftDelete) on purpose: Hibernate's @SoftDelete
 * would cascade a hard delete onto the child collections, destroying exactly the
 * custom values / resources / documents a restore is supposed to bring back.
 */
@Entity
@Table(name = "tickets")
@Filter(name = "teamFilter", condition = "team_id = :teamId")
@EntityListeners(TenantEntityListener.class)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ticket implements TeamOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submitted_by_id", nullable = false)
    private User submittedBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subcategory_id")
    private Subcategory subcategory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    // Read-only FK values preserve imported legacy references when a related row is missing.
    @Column(name = "team_id", insertable = false, updatable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private Long teamReferenceId;

    @Column(name = "submitted_by_id", insertable = false, updatable = false, nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private Long submittedByReferenceId;

    @Column(name = "department_id", insertable = false, updatable = false, nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private Long departmentReferenceId;

    @Column(name = "subcategory_id", insertable = false, updatable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private Long subcategoryReferenceId;

    @Column(name = "project_id", insertable = false, updatable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private Long projectReferenceId;

    @Column(length = 500)
    private String title;

    @Column(name = "title_en", length = 500)
    private String titleEn;

    @Column(name = "title_ar", length = 500)
    private String titleAr;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_en", columnDefinition = "TEXT")
    private String contentEn;

    @Column(name = "content_ar", columnDefinition = "TEXT")
    private String contentAr;

    @Column(length = 250)
    private String websiteName;

    @Column(name = "website_name_en", length = 250)
    private String websiteNameEn;

    @Column(name = "website_name_ar", length = 250)
    private String websiteNameAr;

    @Column(length = 500)
    private String websiteLink;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant submittedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TicketStatus status = TicketStatus.IN_PROGRESS;

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<TicketFieldValue> customValues = new ArrayList<>();

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @OrderBy("displayOrder ASC, id ASC")
    @Builder.Default
    private List<TicketResource> resources = new ArrayList<>();

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @OrderBy("uploadedAt ASC, id ASC")
    @Builder.Default
    private List<TicketDocument> documents = new ArrayList<>();

    /**
     * Recycle bin: set by a soft delete, cleared by a restore. Nullable and unmapped in
     * @Where terms — see the class comment for the scheme.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Who binned the entry — the admin or the owner. Purely informational. */
    @Column(name = "deleted_by_id")
    private Long deletedById;
}
