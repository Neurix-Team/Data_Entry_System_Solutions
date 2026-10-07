package com.dataentry.dto;

import com.dataentry.model.CleanedFile.Status;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class CleanedFileDtos {
    private CleanedFileDtos() {}
    public record Metadata(@NotBlank @Size(max = 250) String title,
                           @NotNull LocalDate cleanedOn, LocalDate dueOn,
                           @Size(max = 500) String sourceReference, @Size(max = 4000) String notes) {}
    public record Update(@NotNull @jakarta.validation.Valid Metadata metadata,
                         @NotNull Status status, @NotNull @PositiveOrZero Long version) {}
    public record Row(Long id, String title, String originalFilename, long sizeBytes, String sha256,
                      Long teamId, String teamName, Long projectId, String projectName,
                      Long departmentId, String departmentName, LocalDate cleanedOn, LocalDate dueOn,
                      String sourceReference, String notes, Status status, String uploadedBy,
                      Instant uploadedAt, Instant updatedAt, Instant startedAt, Instant completedAt, long version) {}
    public record Page(List<Row> items, long total, int page, int totalPages,
                       long ready, long inProgress, long completed) {}
    public record ProjectOption(Long id, String name, Long teamId, String teamName) {}
    public record DepartmentOption(Long id, String name, Long projectId) {}
    public record Options(List<ProjectOption> projects, List<DepartmentOption> departments,
                          long maxFileBytes, List<String> extensions) {}
    public record Filters(Long projectId, Long departmentId, Status status, LocalDate from, LocalDate to,
                          @Size(max = 250) String search) {}
    public record DeleteRequest(@Size(max = 1000) List<@NotNull @Positive Long> ids,
                                @jakarta.validation.Valid Filters filters,
                                @Size(max = 1000) List<@NotNull @Positive Long> excludedIds,
                                @NotNull @Positive Long expectedCount) {}
    public record Deleted(long deleted) {}
}
