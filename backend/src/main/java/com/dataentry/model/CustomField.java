package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.Instant;

@Entity
@Table(name = "custom_fields")
@Filter(name = "teamFilter", condition = "team_id = :teamId")
@EntityListeners(TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomField implements TeamOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subcategory_id")
    private Subcategory subcategory;

    @Column(name = "field_key", nullable = false, length = 100)
    private String fieldKey;

    @Column(nullable = false, length = 150)
    private String label;

    @Column(name = "label_en", length = 150)
    private String labelEn;

    @Column(name = "label_ar", length = 150)
    private String labelAr;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FieldType type;

    @Column(nullable = false)
    @Builder.Default
    private boolean required = true;

    @Column(nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    @Column(length = 2000)
    private String options;

    @Column(name = "options_en", length = 2000)
    private String optionsEn;

    @Column(name = "options_ar", length = 2000)
    private String optionsAr;

    @Column(length = 250)
    private String placeholder;

    @Column(name = "placeholder_en", length = 250)
    private String placeholderEn;

    @Column(name = "placeholder_ar", length = 250)
    private String placeholderAr;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
