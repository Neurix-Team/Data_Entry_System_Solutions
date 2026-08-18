package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.Instant;

@Entity
@Table(name = "users")
@Filter(name = "teamFilter", condition = "team_id = :teamId")
@EntityListeners(TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User implements TeamOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Owning tenant. Null for SUPER_ADMIN (cross-team) accounts and for the DataSeeder
     * bootstrap moment before the default team exists. The Hibernate {@code teamFilter}
     * uses this column to hide users from other teams.
     *
     * <p>Eagerly fetched: the User principal is passed out of the JwtAuthFilter's short
     * transaction and later used in stateless contexts (e.g. AuthController.me, AuthService.toDto)
     * where a lazy proxy would fail with "no Session". It's one FK lookup — negligible.
     *
     * <p>Note on uniqueness: {@code username} is still globally unique at the DB level for
     * SQLite compatibility (composite-unique-constraint removal requires a table rebuild).
     * Enforce per-team uniqueness at the service layer if that becomes a real ask.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "team_id")
    private Team team;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(length = 150)
    private String displayName;

    @Column(name = "display_name_en", length = 150)
    private String displayNameEn;

    @Column(name = "display_name_ar", length = 150)
    private String displayNameAr;

    @Column(length = 200)
    private String email;

    @Column(length = 40)
    private String phone;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /**
     * Timestamp of the last avatar upload. Null means no avatar. Kept on the User row
     * (rather than joining to {@link UserAvatar}) so the frontend can decide whether to
     * render an avatar image without an extra query, and can cache-bust with ?v={value}.
     */
    @Column(name = "avatar_updated_at")
    private Instant avatarUpdatedAt;
}
