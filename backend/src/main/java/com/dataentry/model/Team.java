package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A tenant. Every scoped entity in the system (users, projects, departments, tickets, ...)
 * belongs to exactly one Team. SUPER_ADMIN users have {@code team = null} and can operate
 * across every team; every other role is confined to their own team by the Hibernate
 * {@code teamFilter} enabled per-transaction in {@code TenantFilterAspect}.
 */
@Entity
@Table(name = "teams", uniqueConstraints = {
        @UniqueConstraint(name = "uk_team_slug", columnNames = {"slug"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** URL-safe short id. Immutable after creation — used in JWT claims and audit logs. */
    @Column(nullable = false, length = 60)
    private String slug;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "name_en", length = 150)
    private String nameEn;

    @Column(name = "name_ar", length = 150)
    private String nameAr;

    /** Free-form one-line description shown on the super-admin teams list. */
    @Column(length = 300)
    private String description;

    /** Optional hex colour (e.g. #6366f1) used to tint the team's UI chrome. */
    @Column(length = 20)
    private String color;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** Super-admin user who created the team; nullable for the seeded default team. */
    @Column(name = "created_by_id")
    private Long createdById;
}
