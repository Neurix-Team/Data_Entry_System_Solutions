package com.dataentry.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "cleaned_files")
@Getter @Setter @NoArgsConstructor
public class CleanedFile {
    public enum Status { READY, IN_PROGRESS, COMPLETED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "project_id", nullable = false)
    private Project project;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "department_id", nullable = false)
    private Department department;
    @Column(nullable = false, length = 250)
    private String title;
    @Column(nullable = false, length = 250)
    private String originalFilename;
    @Column(nullable = false, unique = true, length = 100)
    private String storageKey;
    @Column(nullable = false)
    private long sizeBytes;
    @Column(nullable = false, length = 64)
    private String sha256;
    @Column(nullable = false)
    private LocalDate cleanedOn;
    private LocalDate dueOn;
    @Column(length = 500)
    private String sourceReference;
    @Column(length = 4000)
    private String notes;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Status status = Status.READY;
    @Column(nullable = false, length = 150)
    private String uploadedBy;
    @Column(nullable = false)
    private Instant uploadedAt = Instant.now();
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
    private Instant startedAt;
    private Instant completedAt;
    @Version
    private long version;
}
