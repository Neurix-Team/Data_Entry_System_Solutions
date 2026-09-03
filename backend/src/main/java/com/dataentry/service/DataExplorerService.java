package com.dataentry.service;

import com.dataentry.dto.DataExplorerDtos;
import com.dataentry.model.TicketDocument;
import com.dataentry.model.TicketFieldValue;
import com.dataentry.model.TicketStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unified cross-team ticket view used by both the super-admin data explorer page and the
 * external /api/v1/export/tickets endpoint. Runs with the tenant filter disabled — every
 * consumer of this service is either SUPER_ADMIN or an API token, both of which are meant
 * to see every team.
 *
 * <p>Query strategy: one JPQL for the ticket rows, then a single batched-in-clause fetch
 * for documents and field values. That keeps the round-trip count constant regardless of
 * page size (no N+1 across attachments).
 */
@Service
@Transactional(readOnly = true)
public class DataExplorerService {

    /** Cap page size so a curious caller can't ask for a million rows in one go. */
    private static final int MAX_PAGE_SIZE = 500;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final EntityManager em;
    private final JdbcTemplate jdbc;

    public DataExplorerService(EntityManager em,
                               JdbcTemplate jdbc) {
        this.em = em;
        this.jdbc = jdbc;
    }

    /**
     * Paginated cross-team ticket search. {@code cursor} is the id of the last row seen
     * (rows are ordered by id DESC so the cursor gives "everything older than X").
     */
    public DataExplorerDtos.Page search(Filters filters, Long cursor, Integer size, String downloadUrlPrefix) {
        int pageSize = clampSize(size);

        StringBuilder jpql = baseRowQuery("where 1=1 ");
        Map<String, Object> params = new HashMap<>();
        appendFilters(jpql, params, filters);
        if (cursor != null) { jpql.append("and t.id < :cursor "); params.put("cursor", cursor); }

        jpql.append("order by t.id desc");

        TypedQuery<Tuple> q = em.createQuery(jpql.toString(), Tuple.class);
        params.forEach(q::setParameter);
        // Fetch one extra to detect "has more" without a second COUNT round trip.
        q.setMaxResults(pageSize + 1);
        List<BaseRow> rows = q.getResultList().stream().map(this::toBaseRow).toList();

        boolean hasMore = rows.size() > pageSize;
        if (hasMore) rows = rows.subList(0, pageSize);

        // Batched fetches for docs + field values keyed by ticket id.
        List<Long> ticketIds = rows.stream().map(BaseRow::id).toList();
        Map<Long, List<TicketDocument>> docsByTicket = loadDocuments(ticketIds);
        Map<Long, List<TicketFieldValue>> fieldsByTicket = loadFieldValues(ticketIds);

        List<DataExplorerDtos.Row> items = new ArrayList<>(rows.size());
        for (BaseRow row : rows) {
            items.add(toRow(row,
                    docsByTicket.getOrDefault(row.id(), List.of()),
                    fieldsByTicket.getOrDefault(row.id(), List.of()),
                    downloadUrlPrefix));
        }

        Long nextCursor = (hasMore && !items.isEmpty()) ? items.get(items.size() - 1).id() : null;
        long total = countMatching(filters);

        return new DataExplorerDtos.Page(items, nextCursor, hasMore, total);
    }

    /** Facet lists for the explorer sidebar. Kept as separate queries so an empty result
     *  page still shows every possible filter option. */
    public DataExplorerDtos.Facets facets() {
        List<DataExplorerDtos.Named> teams = new ArrayList<>();
        List<DataExplorerDtos.Named> projects = new ArrayList<>();
        List<DataExplorerDtos.Named> users = new ArrayList<>();

        jdbc.query("SELECT id, name FROM teams WHERE active = TRUE ORDER BY name",
                (RowCallbackHandler) rs -> teams.add(new DataExplorerDtos.Named(rs.getLong(1), rs.getString(2))));
        jdbc.query("SELECT id, name FROM projects ORDER BY name",
                (RowCallbackHandler) rs -> projects.add(new DataExplorerDtos.Named(rs.getLong(1), rs.getString(2))));
        jdbc.query("SELECT id, COALESCE(display_name, username) FROM users " +
                        "WHERE active = TRUE AND role != 'SUPER_ADMIN' ORDER BY 2",
                (RowCallbackHandler) rs -> users.add(new DataExplorerDtos.Named(rs.getLong(1), rs.getString(2))));

        return new DataExplorerDtos.Facets(teams, projects, users);
    }

    /** Fetch a single ticket by id — used by the row-expand action in the UI. */
    public DataExplorerDtos.Row byId(Long id, String downloadUrlPrefix) {
        TypedQuery<Tuple> query = em.createQuery(
                baseRowQuery("where t.id = :id").toString(), Tuple.class);
        query.setParameter("id", id);
        query.setMaxResults(1);
        List<Tuple> matches = query.getResultList();
        if (matches.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Ticket not found");
        }
        BaseRow row = toBaseRow(matches.get(0));
        Map<Long, List<TicketDocument>> docs = loadDocuments(List.of(id));
        Map<Long, List<TicketFieldValue>> fields = loadFieldValues(List.of(id));
        return toRow(row, docs.getOrDefault(id, List.of()),
                fields.getOrDefault(id, List.of()), downloadUrlPrefix);
    }

    /**
     * Select scalar values instead of materialising the complete Ticket object graph.
     *
     * <p>Some upgraded installations contain legacy tickets whose required user or
     * department row was removed before foreign-key enforcement was enabled. Fetching a
     * full entity graph makes Hibernate either drop that ticket from a by-id lookup or
     * throw while initialising the missing association, which used to turn the entire
     * explorer page into a 500. Explicit LEFT JOIN scalar projection keeps the historical
     * ticket visible and reports the missing related metadata as null.</p>
     */
    private StringBuilder baseRowQuery(String whereClause) {
        return new StringBuilder(
                "select t.id as id, " +
                        "team.id as teamId, team.name as teamName, " +
                        "project.id as projectId, project.name as projectName, " +
                        "department.id as departmentId, department.name as departmentName, " +
                        "subcategory.id as subcategoryId, subcategory.name as subcategoryName, " +
                        "submitter.id as submitterId, submitter.username as submitterUsername, " +
                        "submitter.displayName as submitterDisplayName, " +
                        "submitter.email as submitterEmail, submitter.phone as submitterPhone, " +
                        "submitter.role as submitterRole, " +
                        "t.title as title, t.content as content, " +
                        "t.websiteName as websiteName, t.websiteLink as websiteLink, " +
                        "t.status as status, t.submittedAt as submittedAt " +
                        "from Ticket t " +
                        "left join t.submittedBy submitter " +
                        "left join t.team team " +
                        "left join t.project project " +
                        "left join t.department department " +
                        "left join t.subcategory subcategory " +
                        whereClause);
    }

    private BaseRow toBaseRow(Tuple row) {
        return new BaseRow(
                row.get("id", Long.class),
                row.get("teamId", Long.class),
                row.get("teamName", String.class),
                row.get("projectId", Long.class),
                row.get("projectName", String.class),
                row.get("departmentId", Long.class),
                row.get("departmentName", String.class),
                row.get("subcategoryId", Long.class),
                row.get("subcategoryName", String.class),
                row.get("submitterId", Long.class),
                row.get("submitterUsername", String.class),
                row.get("submitterDisplayName", String.class),
                row.get("submitterEmail", String.class),
                row.get("submitterPhone", String.class),
                row.get("submitterRole", com.dataentry.model.Role.class),
                row.get("title", String.class),
                row.get("content", String.class),
                row.get("websiteName", String.class),
                row.get("websiteLink", String.class),
                row.get("status", TicketStatus.class),
                row.get("submittedAt", Instant.class));
    }

    /**
     * Every attachment that matches {@code filters}, flattened with the ticket, project,
     * department and subcategory it belongs to. Powers the "download to folder" feature in
     * the explorer: the browser walks this list and mirrors each file into
     * {@code Project/Department[/Subcategory]/} on the operator's machine, so it needs the
     * whole set at once rather than a page.
     *
     * @param includeTickets also return every matching ticket's text and custom fields, so
     *                       the client can write a Markdown sidecar per entry and an index.
     */
    public DataExplorerDtos.Manifest manifest(Filters filters, boolean includeTickets) {
        StringBuilder jpql = new StringBuilder(
                "select d.id as docId, d.name as docName, d.originalFilename as originalFilename, " +
                        "d.contentType as contentType, d.sizeBytes as sizeBytes, d.contentHash as contentHash, " +
                        "t.id as id, t.title as title, t.submittedAt as submittedAt, " +
                        "team.id as teamId, team.name as teamName, " +
                        "project.id as projectId, project.name as projectName, " +
                        "department.id as departmentId, department.name as departmentName, " +
                        "subcategory.id as subcategoryId, subcategory.name as subcategoryName, " +
                        "submitter.displayName as submitterDisplayName, submitter.username as submitterUsername " +
                        "from TicketDocument d " +
                        "join d.ticket t " +
                        "left join t.submittedBy submitter " +
                        "left join t.team team " +
                        "left join t.project project " +
                        "left join t.department department " +
                        "left join t.subcategory subcategory " +
                        "where 1=1 ");
        Map<String, Object> params = new HashMap<>();
        appendFilters(jpql, params, filters);
        jpql.append("order by t.id desc, d.id asc");

        TypedQuery<Tuple> q = em.createQuery(jpql.toString(), Tuple.class);
        params.forEach(q::setParameter);

        List<DataExplorerDtos.ManifestEntry> files = new ArrayList<>();
        Set<Long> ticketsWithFiles = new HashSet<>();
        long bytes = 0;
        for (Tuple r : q.getResultList()) {
            String display = r.get("submitterDisplayName", String.class);
            String submitter = display != null && !display.isBlank() ? display : r.get("submitterUsername", String.class);
            long size = r.get("sizeBytes", Long.class) == null ? 0 : r.get("sizeBytes", Long.class);
            bytes += size;
            ticketsWithFiles.add(r.get("id", Long.class));
            files.add(new DataExplorerDtos.ManifestEntry(
                    r.get("id", Long.class),
                    r.get("title", String.class),
                    r.get("submittedAt", Instant.class),
                    r.get("teamId", Long.class),
                    r.get("teamName", String.class),
                    r.get("projectId", Long.class),
                    r.get("projectName", String.class),
                    r.get("departmentId", Long.class),
                    r.get("departmentName", String.class),
                    r.get("subcategoryId", Long.class),
                    r.get("subcategoryName", String.class),
                    submitter,
                    r.get("docId", Long.class),
                    r.get("docName", String.class),
                    r.get("originalFilename", String.class),
                    r.get("contentType", String.class),
                    size,
                    r.get("contentHash", String.class)));
        }

        List<DataExplorerDtos.ManifestTicket> tickets = includeTickets ? manifestTickets(filters) : List.of();
        long totalTickets = includeTickets ? tickets.size() : ticketsWithFiles.size();
        return new DataExplorerDtos.Manifest(files, tickets, files.size(), bytes, totalTickets);
    }

    /** All matching tickets (with or without files) with their text and custom fields. */
    private List<DataExplorerDtos.ManifestTicket> manifestTickets(Filters filters) {
        StringBuilder jpql = baseRowQuery("where 1=1 ");
        Map<String, Object> params = new HashMap<>();
        appendFilters(jpql, params, filters);
        jpql.append("order by t.id desc");
        TypedQuery<Tuple> q = em.createQuery(jpql.toString(), Tuple.class);
        params.forEach(q::setParameter);
        List<BaseRow> rows = q.getResultList().stream().map(this::toBaseRow).toList();

        // Field values in chunks — a very large filter set must not blow the IN-list limit.
        Map<Long, List<TicketFieldValue>> fields = new HashMap<>();
        List<Long> ids = rows.stream().map(BaseRow::id).toList();
        for (int i = 0; i < ids.size(); i += 500) {
            fields.putAll(loadFieldValues(ids.subList(i, Math.min(ids.size(), i + 500))));
        }

        List<DataExplorerDtos.ManifestTicket> out = new ArrayList<>(rows.size());
        for (BaseRow row : rows) {
            List<DataExplorerDtos.FieldValue> fv = fields.getOrDefault(row.id(), List.of()).stream()
                    .map(v -> new DataExplorerDtos.FieldValue(
                            v.getField() != null ? v.getField().getId() : null,
                            v.getField() != null ? v.getField().getLabel() : null,
                            v.getValue()))
                    .toList();
            String submitter = row.submitterDisplayName() != null && !row.submitterDisplayName().isBlank()
                    ? row.submitterDisplayName() : row.submitterUsername();
            out.add(new DataExplorerDtos.ManifestTicket(
                    row.id(), row.title(), row.content(), row.websiteName(), row.websiteLink(),
                    row.status() != null ? row.status().name() : null, row.submittedAt(), submitter,
                    row.teamName(), row.projectName(), row.departmentName(), row.subcategoryName(), fv));
        }
        return out;
    }

    /** Shared WHERE fragment so the page, the count and the manifest always agree. */
    private void appendFilters(StringBuilder jpql, Map<String, Object> params, Filters filters) {
        if (filters.teamId() != null) { jpql.append("and t.team.id = :teamId "); params.put("teamId", filters.teamId()); }
        if (filters.projectId() != null) { jpql.append("and t.project.id = :projectId "); params.put("projectId", filters.projectId()); }
        if (filters.userId() != null) { jpql.append("and t.submittedBy.id = :userId "); params.put("userId", filters.userId()); }
        if (filters.from() != null) { jpql.append("and t.submittedAt >= :from "); params.put("from", filters.from()); }
        if (filters.to() != null) { jpql.append("and t.submittedAt < :to "); params.put("to", filters.to()); }
        if (filters.search() != null && !filters.search().isBlank()) {
            jpql.append("and (lower(t.title) like :q or lower(t.content) like :q or lower(t.websiteName) like :q) ");
            params.put("q", "%" + filters.search().toLowerCase() + "%");
        }
    }

    private long countMatching(Filters filters) {
        StringBuilder jpql = new StringBuilder("select count(t) from Ticket t where 1=1 ");
        Map<String, Object> params = new HashMap<>();
        appendFilters(jpql, params, filters);
        TypedQuery<Long> q = em.createQuery(jpql.toString(), Long.class);
        params.forEach(q::setParameter);
        Long v = q.getSingleResult();
        return v == null ? 0 : v;
    }

    private Map<Long, List<TicketDocument>> loadDocuments(List<Long> ticketIds) {
        Map<Long, List<TicketDocument>> out = new HashMap<>();
        if (ticketIds.isEmpty()) return out;
        List<TicketDocument> docs = em.createQuery(
                        "select d from TicketDocument d where d.ticket.id in :ids order by d.uploadedAt asc, d.id asc",
                        TicketDocument.class)
                .setParameter("ids", ticketIds)
                .getResultList();
        for (TicketDocument d : docs) {
            out.computeIfAbsent(d.getTicket().getId(), k -> new ArrayList<>()).add(d);
        }
        return out;
    }

    private Map<Long, List<TicketFieldValue>> loadFieldValues(List<Long> ticketIds) {
        Map<Long, List<TicketFieldValue>> out = new HashMap<>();
        if (ticketIds.isEmpty()) return out;
        List<TicketFieldValue> vals = em.createQuery(
                        "select v from TicketFieldValue v " +
                        "join fetch v.field " +
                                "where v.ticket.id in :ids order by v.ticket.id, v.field.id, v.id",
                        TicketFieldValue.class)
                .setParameter("ids", ticketIds)
                .getResultList();
        for (TicketFieldValue v : vals) {
            out.computeIfAbsent(v.getTicket().getId(), k -> new ArrayList<>()).add(v);
        }
        return out;
    }

    private DataExplorerDtos.Row toRow(BaseRow row,
                                       List<TicketDocument> docs,
                                       List<TicketFieldValue> fields,
                                       String downloadUrlPrefix) {
        List<DataExplorerDtos.DocumentSummary> docSummaries = docs.stream()
                .map(d -> new DataExplorerDtos.DocumentSummary(
                        d.getId(), d.getName(), d.getOriginalFilename(),
                        d.getContentType(), d.getSizeBytes(),
                        d.getContentHash(), d.getUploadedAt(),
                        downloadUrlPrefix == null ? null : downloadUrlPrefix + d.getId() + "/download"
                ))
                .toList();
        List<DataExplorerDtos.FieldValue> fieldValues = fields.stream()
                .map(v -> new DataExplorerDtos.FieldValue(
                        v.getField() != null ? v.getField().getId() : null,
                        v.getField() != null ? v.getField().getLabel() : null,
                        v.getValue()))
                .toList();
        return new DataExplorerDtos.Row(
                row.id(),
                row.teamId(),
                row.teamName(),
                row.projectId(),
                row.projectName(),
                row.departmentId(),
                row.departmentName(),
                row.subcategoryId(),
                row.subcategoryName(),
                row.submitterId(),
                row.submitterUsername(),
                row.submitterDisplayName(),
                row.submitterEmail(),
                row.submitterPhone(),
                row.submitterRole() != null ? row.submitterRole().name() : null,
                row.title(),
                row.content(),
                row.websiteName(),
                row.websiteLink(),
                row.status() != null ? row.status().name() : null,
                row.submittedAt(),
                docSummaries,
                fieldValues
        );
    }

    private int clampSize(Integer size) {
        if (size == null || size <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(size, MAX_PAGE_SIZE);
    }

    public record Filters(
            Long teamId,
            Long projectId,
            Long userId,
            Instant from,
            Instant to,
            String search
    ) {}

    private record BaseRow(
            Long id,
            Long teamId,
            String teamName,
            Long projectId,
            String projectName,
            Long departmentId,
            String departmentName,
            Long subcategoryId,
            String subcategoryName,
            Long submitterId,
            String submitterUsername,
            String submitterDisplayName,
            String submitterEmail,
            String submitterPhone,
            com.dataentry.model.Role submitterRole,
            String title,
            String content,
            String websiteName,
            String websiteLink,
            TicketStatus status,
            Instant submittedAt
    ) {}
}
