package com.dataentry.service;

import com.dataentry.dto.AdminFeatureDtos;
import com.dataentry.model.CustomField;
import com.dataentry.model.Department;
import com.dataentry.model.Project;
import com.dataentry.model.Subcategory;
import com.dataentry.model.Ticket;
import com.dataentry.model.TicketFieldValue;
import com.dataentry.model.TicketStatus;
import com.dataentry.model.User;
import com.dataentry.repository.CustomFieldRepository;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.ProjectRepository;
import com.dataentry.repository.SubcategoryRepository;
import com.dataentry.repository.TicketRepository;
import com.dataentry.security.TenantGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CSV import (C1): bulk-create data entries from a spreadsheet export. Excel users
 * "Save As CSV" — no binary formats to parse, so the RFC 4180 parser below is the
 * whole story. Row-level errors never abort the import: every healthy row goes in,
 * every broken one comes back with its line number and reason.
 */
@Service
public class CsvImportService {

    private static final int MAX_ROWS = 5000;
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private final TicketRepository tickets;
    private final DepartmentRepository departments;
    private final ProjectRepository projects;
    private final SubcategoryRepository subcategories;
    private final CustomFieldRepository fields;
    private final AuditService audit;
    private final int maxRows;

    public CsvImportService(TicketRepository tickets,
                            DepartmentRepository departments,
                            ProjectRepository projects,
                            SubcategoryRepository subcategories,
                            CustomFieldRepository fields,
                            AuditService audit,
                            @Value("${app.import.max-rows:5000}") int maxRows) {
        this.tickets = tickets;
        this.departments = departments;
        this.projects = projects;
        this.subcategories = subcategories;
        this.fields = fields;
        this.audit = audit;
        this.maxRows = Math.max(1, Math.min(maxRows, MAX_ROWS));
    }

    @Transactional
    public AdminFeatureDtos.ImportResult importCsv(User admin, MultipartFile file) throws IOException {
        if (admin == null || admin.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a CSV file first.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "File too large — keep imports under " + (MAX_FILE_BYTES / 1024 / 1024) + " MB.");
        }
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        if (content.startsWith("﻿")) {
            content = content.substring(1); // strip the UTF-8 BOM Excel loves to add
        }
        List<List<String>> rows = parseCsv(content);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file is empty.");
        }
        List<String> header = rows.get(0).stream().map(h -> h.trim().toLowerCase()).toList();
        int titleCol = header.indexOf("title");
        int contentCol = header.indexOf("content");
        if (titleCol < 0 || contentCol < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The CSV must have 'title' and 'content' columns.");
        }
        List<List<String>> data = rows.subList(1, rows.size());
        if (data.size() > maxRows) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Too many rows (" + data.size() + ") — the cap is " + maxRows + ". Split the file.");
        }

        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            colIndex.put(header.get(i), i);
        }

        List<AdminFeatureDtos.ImportRowError> errors = new ArrayList<>();
        List<Ticket> toSave = new ArrayList<>();
        for (int r = 0; r < data.size(); r++) {
            int rowNumber = r + 2; // 1 header + 1-based
            List<String> cells = data.get(r);
            try {
                toSave.add(buildRow(admin, cells, colIndex, titleCol, contentCol));
            } catch (ResponseStatusException e) {
                errors.add(new AdminFeatureDtos.ImportRowError(rowNumber,
                        e.getReason() == null ? "invalid row" : e.getReason()));
            } catch (Exception e) {
                errors.add(new AdminFeatureDtos.ImportRowError(rowNumber, e.toString()));
            }
        }

        int created = 0;
        for (int i = 0; i < toSave.size(); i += 50) {
            List<Ticket> chunk = toSave.subList(i, Math.min(i + 50, toSave.size()));
            tickets.saveAll(chunk);
            tickets.flush();
            created += chunk.size();
        }
        audit.record(AuditService.Action.CREATE, AuditService.EntityType.TICKET, null,
                "csv import: created=" + created + " failed=" + errors.size());
        return new AdminFeatureDtos.ImportResult(created, errors.size(), 1, errors);
    }

    // ── row building ─────────────────────────────────────────────────────────

    private Ticket buildRow(User admin, List<String> cells, Map<String, Integer> colIndex,
                            int titleCol, int contentCol) {
        String title = cell(cells, titleCol);
        String content = cell(cells, contentCol);
        if (title == null || title.isBlank()) throw bad("missing title");
        if (content == null || content.isBlank()) throw bad("missing content");
        if (title.length() > 500) throw bad("title longer than 500 characters");
        title = title.trim();
        content = content.trim();
        String websiteName = truncate(cell(cells, colIndex.get("websitename")), 250);
        String websiteLink = truncate(cell(cells, colIndex.get("websitelink")), 500);
        if (websiteLink != null && !websiteLink.isBlank()) {
            websiteLink = websiteLink.trim();
            if (!(websiteLink.startsWith("http://") || websiteLink.startsWith("https://"))) {
                throw bad("websiteLink must start with http:// or https://");
            }
        } else {
            websiteLink = "";
        }

        Department dept = resolveDepartment(cells, colIndex);
        Subcategory sub = resolveSubcategory(cells, colIndex, dept);
        Project project = resolveProject(cells, colIndex, dept);

        Ticket t = Ticket.builder()
                .submittedBy(admin)
                .department(dept)
                .subcategory(sub)
                .project(project)
                .title(title)
                .content(content)
                .websiteName(websiteName)
                .websiteLink(websiteLink)
                .status(TicketStatus.IN_PROGRESS)
                .build();
        t.setTitleEn(title);
        t.setTitleAr(title);
        t.setContentEn(content);
        t.setContentAr(content);
        t.setWebsiteNameEn(websiteName);
        t.setWebsiteNameAr(websiteName);
        applyCustomFields(t, cells, colIndex, sub);
        return t;
    }

    /** Extra columns map onto custom fields by field key or label (case-insensitive). */
    private void applyCustomFields(Ticket t, List<String> cells, Map<String, Integer> colIndex,
                                   Subcategory sub) {
        if (sub == null || sub.getId() == null) return;
        List<CustomField> activeFields = fields
                .findAllBySubcategoryIdAndActiveTrueOrderByDisplayOrderAscIdAsc(sub.getId());
        if (activeFields.isEmpty()) return;
        for (CustomField f : activeFields) {
            String value = colValueForField(cells, colIndex, f);
            String stored = value == null ? "" : value.trim();
            if (f.isRequired() && stored.isBlank()) {
                throw bad("required field '" + f.getLabel() + "' is empty");
            }
            if (stored.length() > 2000000) throw bad("field '" + f.getLabel() + "' too long");
            TicketFieldValue v = TicketFieldValue.builder()
                    .ticket(t)
                    .field(f)
                    .value(stored)
                    .build();
            v.setValueEn(stored);
            v.setValueAr(stored);
            t.getCustomValues().add(v);
        }
    }

    private String colValueForField(List<String> cells, Map<String, Integer> colIndex, CustomField f) {
        for (String key : List.of(f.getFieldKey(), f.getLabel(), f.getLabelEn(), f.getLabelAr())) {
            if (key == null || key.isBlank()) continue;
            Integer idx = colIndex.get(key.trim().toLowerCase());
            if (idx != null) return cell(cells, idx);
        }
        return null;
    }

    // ── resolvers ─────────────────────────────────────────────────────────────

    private Department resolveDepartment(List<String> cells, Map<String, Integer> colIndex) {
        Long deptId = longOrNull(cell(cells, colIndex.get("departmentid")));
        if (deptId != null) {
            Department dept = departments.findById(deptId)
                    .orElseThrow(() -> bad("departmentId " + deptId + " not found"));
            TenantGuard.assertOwnership(dept);
            if (!dept.isActive()) throw bad("department '" + dept.getName() + "' is inactive");
            return dept;
        }
        String name = cell(cells, colIndex.get("departmentname"));
        if (name != null && !name.isBlank()) {
            Department dept = departments.findAllByActiveTrueOrderByNameAsc().stream()
                    .filter(d -> d.getName() != null && d.getName().equalsIgnoreCase(name.trim()))
                    .findFirst()
                    .orElseThrow(() -> bad("no active department named \"" + name.trim() + "\""));
            TenantGuard.assertOwnership(dept);
            return dept;
        }
        throw bad("each row needs departmentId or departmentName");
    }

    private Subcategory resolveSubcategory(List<String> cells, Map<String, Integer> colIndex, Department dept) {
        Long subId = longOrNull(cell(cells, colIndex.get("subcategoryid")));
        if (subId == null) return null;
        Subcategory sub = subcategories.findById(subId)
                .orElseThrow(() -> bad("subcategoryId " + subId + " not found"));
        TenantGuard.assertOwnership(sub);
        if (!sub.isActive()) throw bad("subcategoryId " + subId + " is inactive");
        Long subDept = sub.getDepartment() == null ? null : sub.getDepartment().getId();
        if (dept == null || subDept == null || !subDept.equals(dept.getId())) {
            throw bad("subcategoryId " + subId + " does not belong to that department");
        }
        return sub;
    }

    private Project resolveProject(List<String> cells, Map<String, Integer> colIndex, Department dept) {
        Long projectId = longOrNull(cell(cells, colIndex.get("projectid")));
        if (projectId != null) {
            Project p = projects.findById(projectId)
                    .orElseThrow(() -> bad("projectId " + projectId + " not found"));
            TenantGuard.assertOwnership(p);
            if (p.getDeletedAt() != null) throw bad("projectId " + projectId + " is in the recycle bin");
            if (dept == null || dept.getProject() == null
                    || !dept.getProject().getId().equals(p.getId())) {
                throw bad("projectId " + projectId + " does not match that department");
            }
            return p;
        }
        String name = cell(cells, colIndex.get("projectname"));
        if (name != null && !name.isBlank()) {
            return projects.findAllByOrderByCreatedAtDesc().stream()
                    .filter(p -> p.getName() != null && p.getName().equalsIgnoreCase(name.trim()))
                    .findFirst()
                    .orElse(null); // project is optional — the entry still files under the department
        }
        return dept == null ? null : dept.getProject();
    }

    // ── parser + small helpers ───────────────────────────────────────────────

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static String cell(List<String> cells, Integer idx) {
        if (idx == null || idx < 0 || idx >= cells.size()) return null;
        String v = cells.get(idx);
        return v == null ? null : v.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static Long longOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * RFC 4180 parser: quoted cells, doubled quotes inside quotes, embedded commas
     * and newlines, CRLF or LF line endings, tolerates a missing final newline.
     */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (c != '\r') {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}