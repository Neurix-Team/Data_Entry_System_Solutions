package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tickets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submitted_by_id", nullable = false)
    private User submittedBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subcategory_id")
    private Subcategory subcategory;

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
    @Builder.Default
    private List<TicketFieldValue> customValues = new ArrayList<>();
}
