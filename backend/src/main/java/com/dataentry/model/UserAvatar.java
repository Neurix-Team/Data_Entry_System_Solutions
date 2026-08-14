package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Binary profile picture for a {@link User}. Kept in a side table so the frequently-read
 * {@code users} rows stay small; only the avatar endpoint pulls this in.
 */
@Entity
@Table(name = "user_avatars")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAvatar {

    /** Shared PK with users.id — one avatar per user. */
    @Id
    private Long userId;

    @Column(name = "content_type", nullable = false, length = 80)
    private String contentType;

    // SQLite JDBC does not implement ResultSet.getBlob(), so we cannot use @Lob here — it
    // makes Hibernate go down the Blob path and every avatar fetch dies with
    // SQLFeatureNotSupportedException. Plain byte[] maps to VARBINARY, which the driver
    // handles via getBytes(). Avatars are capped at 2 MB, so no streaming is needed.
    @Column(name = "data", nullable = false, columnDefinition = "BLOB")
    private byte[] data;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
