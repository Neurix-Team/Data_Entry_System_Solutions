package com.dataentry.service;

import com.dataentry.dto.DataExplorerDtos;
import com.dataentry.dto.DatasetDtos;
import com.dataentry.model.DatasetRecord;
import com.dataentry.repository.DatasetRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@Service
public class DatasetService {
    private static final int EXPORT_PAGE_SIZE = 500;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 500;
    private static final String DOWNLOAD_PREFIX = "/api/v1/export/documents/";

    private final DatasetRecordRepository repository;
    private final DataExplorerService explorer;
    private final ObjectMapper mapper;
    private final EntityManager em;

    public DatasetService(DatasetRecordRepository repository,
                          DataExplorerService explorer,
                          ObjectMapper mapper,
                          EntityManager em) {
        this.repository = repository;
        this.explorer = explorer;
        this.mapper = mapper;
        this.em = em;
    }

    /** Publish all current source rows. One transaction makes the button all-or-nothing. */
    @Transactional
    public DatasetDtos.PublishResult publish() {
        int scanned = 0, inserted = 0, updated = 0, unchanged = 0;
        Long cursor = null;
        DataExplorerService.Filters all = new DataExplorerService.Filters(null, null, null, null, null, null);
        do {
            DataExplorerDtos.Page page = explorer.search(all, cursor, EXPORT_PAGE_SIZE, DOWNLOAD_PREFIX);
            List<Long> ids = page.items().stream().map(DataExplorerDtos.Row::id).toList();
            Map<Long, DatasetRecord> existing = new HashMap<>();
            if (!ids.isEmpty()) {
                repository.findAllBySourceTicketIdIn(ids)
                        .forEach(r -> existing.put(r.getSourceTicketId(), r));
            }
            List<DatasetRecord> writes = new ArrayList<>();
            for (DataExplorerDtos.Row source : page.items()) {
                scanned++;
                String attachments = json(source.documents());
                String customFields = json(source.customFields());
                String fingerprint = fingerprint(source, attachments, customFields);
                DatasetRecord target = existing.get(source.id());
                if (target == null) {
                    target = new DatasetRecord();
                    target.setSourceTicketId(source.id());
                    target.setPublishedAt(Instant.now());
                    inserted++;
                } else if (fingerprint.equals(target.getSourceFingerprint())
                        && Objects.equals(target.getAttachmentCount(), source.documents().size())) {
                    unchanged++;
                    continue;
                } else {
                    updated++;
                }
                copy(source, target, attachments, customFields, fingerprint);
                writes.add(target);
            }
            if (!writes.isEmpty()) repository.saveAll(writes);
            cursor = page.nextCursor();
            if (!page.hasMore()) break;
        } while (cursor != null);

        return new DatasetDtos.PublishResult(scanned, inserted, updated, unchanged, repository.count());
    }

    @Transactional(readOnly = true)
    public DatasetDtos.Page list(Long cursor, Integer requestedSize) {
        int size = requestedSize == null || requestedSize < 1
                ? DEFAULT_PAGE_SIZE : Math.min(requestedSize, MAX_PAGE_SIZE);
        var pageable = PageRequest.of(0, size + 1);
        List<DatasetRecord> found = cursor == null
                ? repository.findAllByOrderByIdDesc(pageable)
                : repository.findAllByIdLessThanOrderByIdDesc(cursor, pageable);
        boolean hasMore = found.size() > size;
        if (hasMore) found = found.subList(0, size);
        List<DatasetDtos.Row> rows = found.stream().map(this::toDto).toList();
        Long next = hasMore && !rows.isEmpty() ? rows.get(rows.size() - 1).id() : null;
        return new DatasetDtos.Page(rows, next, hasMore, repository.count());
    }

    /** Fast counters for the coloured summary cards. Attachment counts come from each
     * published snapshot, so a file added later appears as pending until Publish runs. */
    @Transactional(readOnly = true)
    public DatasetDtos.Stats stats() {
        long totalRecords = em.createQuery("select count(t) from Ticket t", Long.class)
                .getSingleResult();
        long publishedRecords = em.createQuery(
                        "select count(r) from DatasetRecord r " +
                                "where r.sourceTicketId in (select t.id from Ticket t)", Long.class)
                .getSingleResult();
        long totalFiles = em.createQuery("select count(d) from TicketDocument d", Long.class)
                .getSingleResult();
        Long snapshotFiles = em.createQuery(
                        "select sum(r.attachmentCount) from DatasetRecord r " +
                                "where r.sourceTicketId in (select t.id from Ticket t)", Long.class)
                .getSingleResult();
        long publishedFiles = Math.min(totalFiles, snapshotFiles == null ? 0 : snapshotFiles);
        return new DatasetDtos.Stats(
                publishedRecords,
                Math.max(0, totalRecords - publishedRecords),
                totalRecords,
                publishedFiles,
                Math.max(0, totalFiles - publishedFiles),
                totalFiles);
    }

    private void copy(DataExplorerDtos.Row s, DatasetRecord t, String attachments,
                      String customFields, String fingerprint) {
        t.setTeamId(s.teamId()); t.setTeamName(s.teamName());
        t.setProjectId(s.projectId()); t.setProjectName(s.projectName());
        t.setDepartmentId(s.departmentId()); t.setDepartmentName(s.departmentName());
        t.setSubcategoryId(s.subcategoryId()); t.setSubcategoryName(s.subcategoryName());
        t.setSubmittedByUserId(s.submittedByUserId());
        t.setSubmittedByUsername(s.submittedByUsername());
        t.setSubmittedByDisplayName(s.submittedByDisplayName());
        t.setSubmittedByEmail(s.submittedByEmail()); t.setSubmittedByPhone(s.submittedByPhone());
        t.setSubmittedByRole(s.submittedByRole());
        t.setTitle(s.title()); t.setContent(s.content());
        t.setWebsiteName(s.websiteName()); t.setWebsiteLink(s.websiteLink());
        t.setStatus(s.status()); t.setSubmittedAt(s.submittedAt());
        t.setAttachmentsJson(attachments); t.setCustomFieldsJson(customFields);
        t.setAttachmentCount(s.documents().size());
        t.setSourceFingerprint(fingerprint); t.setRefreshedAt(Instant.now());
    }

    private String fingerprint(DataExplorerDtos.Row source, String attachments, String customFields) {
        try {
            String material = mapper.writeValueAsString(Arrays.asList(
                    source.id(), source.teamId(), source.teamName(), source.projectId(), source.projectName(),
                    source.departmentId(), source.departmentName(), source.subcategoryId(), source.subcategoryName(),
                    source.submittedByUserId(), source.submittedByUsername(), source.submittedByDisplayName(),
                    source.submittedByEmail(), source.submittedByPhone(), source.submittedByRole(),
                    source.title(), source.content(), source.websiteName(), source.websiteLink(),
                    source.status(), source.submittedAt(), attachments, customFields));
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not fingerprint dataset row", e);
        }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? List.of() : value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Could not serialise dataset row", e); }
    }

    private DatasetDtos.Row toDto(DatasetRecord r) {
        return new DatasetDtos.Row(
                r.getId(), r.getSourceTicketId(), r.getTeamId(), r.getTeamName(),
                r.getProjectId(), r.getProjectName(), r.getDepartmentId(), r.getDepartmentName(),
                r.getSubcategoryId(), r.getSubcategoryName(), r.getSubmittedByUserId(),
                r.getSubmittedByUsername(), r.getSubmittedByDisplayName(), r.getSubmittedByEmail(),
                r.getSubmittedByPhone(), r.getSubmittedByRole(), r.getTitle(), r.getContent(),
                r.getWebsiteName(), r.getWebsiteLink(), r.getStatus(), r.getSubmittedAt(),
                read(r.getAttachmentsJson(), new TypeReference<List<DataExplorerDtos.DocumentSummary>>() {}),
                read(r.getCustomFieldsJson(), new TypeReference<List<DataExplorerDtos.FieldValue>>() {}),
                r.getPublishedAt(), r.getRefreshedAt());
    }

    private <T> T read(String value, TypeReference<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Could not read dataset row", e); }
    }
}
