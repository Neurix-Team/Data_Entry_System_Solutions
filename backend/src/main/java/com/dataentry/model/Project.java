package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "projects")
@Filter(name = "teamFilter", condition = "team_id = :teamId")
@EntityListeners(TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project implements TeamOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant. Enforced NOT NULL at the app layer via {@code TenantEntityListener}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "name_en", length = 200)
    private String nameEn;

    @Column(name = "name_ar", length = 200)
    private String nameAr;

    @Column(length = 250)
    private String subtitle;

    @Column(name = "subtitle_en", length = 250)
    private String subtitleEn;

    @Column(name = "subtitle_ar", length = 250)
    private String subtitleAr;

    /**
     * Legacy "primary department" pointer — kept for backward compatibility with data
     * created before Projects owned Departments. On new projects we auto-fill this with
     * whichever department id first ends up in {@link #getDepartments()} so old callers
     * that read {@code project.department} still see a value. The source of truth for
     * "which departments are in this project" is now {@link Department#getProject()}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    /**
     * Departments assigned to this project. Populated via {@link Department#getProject()};
     * this side is read-only and derived. Kept as transient to avoid Hibernate managing
     * the association twice — the {@code Department.project} side owns writes.
     */
    @Transient
    @Builder.Default
    private java.util.List<Department> departments = new java.util.ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "project_members",
            joinColumns = @JoinColumn(name = "project_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    @Builder.Default
    private Set<User> members = new HashSet<>();

    private LocalDate startDate;
    private LocalDate endDate;

    @Column(nullable = false)
    @Builder.Default
    private int progress = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ProjectStatus status = ProjectStatus.ON_TRACK;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
