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

    @Column(name = "avatar_updated_at")
    private Instant avatarUpdatedAt;

    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private long tokenVersion = 0L;

    public boolean isAdminLike() {
        return role == Role.ADMIN || role == Role.SUPER_ADMIN;
    }
}
