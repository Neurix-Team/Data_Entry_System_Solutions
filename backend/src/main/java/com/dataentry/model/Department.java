package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.Instant;

@Entity
@Table(name = "departments")
@Filter(name = "teamFilter", condition = "team_id = :teamId")
@EntityListeners(TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Department implements TeamOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    /**
     * Department names are unique per team, not globally. The {@code unique = true} on the
     * column has to stay (removing it in SQLite requires a table rebuild) but the
     * DepartmentService performs the actual per-team lookup so two teams can both have a
     * "Marketing" department once the legacy unique index is dropped by the startup migration.
     */
    @Column(nullable = false, unique = true, length = 150)
    private String name;

    @Column(name = "name_en", length = 150)
    private String nameEn;

    @Column(name = "name_ar", length = 150)
    private String nameAr;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /**
     * The project this department belongs to. Optional — a department can exist without a
     * project. Set/cleared via the admin project screen's departments picker.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;
}
