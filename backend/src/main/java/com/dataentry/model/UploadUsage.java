package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "upload_usage")
@Getter @Setter @NoArgsConstructor
public class UploadUsage {
    @Id private Long userId;
    @Column(nullable = false) private Instant windowStartedAt;
    @Column(nullable = false) private long chargedBytes;
}
