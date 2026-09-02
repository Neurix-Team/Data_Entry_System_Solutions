package com.dataentry.service;

import com.dataentry.dto.DataExplorerDtos;
import com.dataentry.dto.DatasetDtos;
import com.dataentry.model.DatasetRecord;
import com.dataentry.repository.DatasetRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatasetServiceTest {
    @Mock DatasetRecordRepository repository;
    @Mock DataExplorerService explorer;
    @Mock EntityManager em;

    @Test
    void publish_isIdempotentForAnUnchangedTicket() {
        DataExplorerDtos.Row source = new DataExplorerDtos.Row(
                7L, 1L, "Team", 2L, "Project", 3L, "Department",
                null, null, 4L, "user", "User", "u@example.com", "0100", "USER",
                "Title", "Content", null, null, "REVIEW",
                Instant.parse("2026-09-02T10:00:00Z"),
                List.of(new DataExplorerDtos.DocumentSummary(
                        9L, "File", "file.pdf", "application/pdf", 123L,
                        Instant.parse("2026-09-02T10:01:00Z"), "/api/v1/export/documents/9/download")),
                List.of());
        when(explorer.search(any(), any(), any(), any()))
                .thenReturn(new DataExplorerDtos.Page(List.of(source), null, false, 1));

        AtomicReference<DatasetRecord> stored = new AtomicReference<>();
        when(repository.findAllBySourceTicketIdIn(List.of(7L)))
                .thenAnswer(inv -> stored.get() == null ? List.of() : List.of(stored.get()));
        when(repository.saveAll(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<DatasetRecord> rows = inv.getArgument(0);
            stored.set(rows.get(0));
            return rows;
        });
        when(repository.count()).thenReturn(1L);

        DatasetService service = new DatasetService(
                repository, explorer, new ObjectMapper().findAndRegisterModules(), em);

        DatasetDtos.PublishResult first = service.publish();
        DatasetDtos.PublishResult second = service.publish();

        assertThat(first.inserted()).isEqualTo(1);
        assertThat(first.updated()).isZero();
        assertThat(second.inserted()).isZero();
        assertThat(second.updated()).isZero();
        assertThat(second.unchanged()).isEqualTo(1);
        assertThat(stored.get().getAttachmentsJson()).contains("file.pdf");
        assertThat(stored.get().getSourceFingerprint()).hasSize(64);
        assertThat(stored.get().getAttachmentCount()).isEqualTo(1);
    }
}
