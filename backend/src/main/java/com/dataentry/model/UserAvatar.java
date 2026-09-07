package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "user_avatars")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAvatar {

    @Id
    private Long userId;

    @Column(name = "content_type", nullable = false, length = 80)
    private String contentType;

    @Column(name = "data", nullable = false, length = 2 * 1024 * 1024)
    private byte[] data;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
