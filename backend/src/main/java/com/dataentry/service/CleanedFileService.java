package com.dataentry.service;

import com.dataentry.dto.CleanedFileDtos;
import com.dataentry.model.CleanedFile;
import com.dataentry.model.CleanedFile.Status;
import com.dataentry.repository.*;
import com.dataentry.security.TenantContext;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class CleanedFileService {
    private static final List<String> EXTENSIONS = List.of("txt", "md", "csv", "tsv", "json", "jsonl", "ndjson", "zip", "pdf", "docx", "xlsx", "parquet");
    private static final Set<String> MIME = Set.of("text/plain", "text/csv", "text/tab-separated-values", "text/markdown",
            "application/json", "application/x-ndjson", "application/zip", "application/x-zip-compressed",
            "application/pdf", "application/x-tika-ooxml", "application/x-parquet", "application/vnd.apache.parquet",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final Tika TIKA = new Tika();
    private final CleanedFileRepository files;
    private final ProjectRepository projects;
    private final DepartmentRepository departments;
    private final UserRepository users;
    private final UploadQuotaService quota;
    private final JdbcTemplate jdbc;
    private final Path directory;
    private final long maxBytes;

    public CleanedFileService(CleanedFileRepository files, ProjectRepository projects, DepartmentRepository departments,
                             UserRepository users, UploadQuotaService quota, JdbcTemplate jdbc,
                             @Value("${app.cleaned-files.dir:${user.dir}/data/cleaned-files}") String directory,
                             @Value("${app.cleaned-files.max-file-bytes:52428800}") long maxBytes) {
        this.files = files; this.projects = projects; this.departments = departments; this.users = users;
        this.quota = quota; this.jdbc = jdbc; this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    public CleanedFileDtos.Options options() {
        requireSuper();
        var ps = jdbc.query("SELECT p.id, p.name, p.team_id, t.name FROM projects p LEFT JOIN teams t ON t.id=p.team_id WHERE p.deleted_at IS NULL ORDER BY p.name",
                (rs, n) -> new CleanedFileDtos.ProjectOption(rs.getLong(1), rs.getString(2), rs.getObject(3, Long.class), rs.getString(4)));
        var ds = jdbc.query("SELECT d.id, d.name, d.project_id FROM departments d JOIN projects p ON p.id=d.project_id WHERE d.active=TRUE AND p.deleted_at IS NULL ORDER BY d.name",
                (rs, n) -> new CleanedFileDtos.DepartmentOption(rs.getLong(1), rs.getString(2), rs.getLong(3)));
        return new CleanedFileDtos.Options(ps, ds, maxBytes, EXTENSIONS);
    }

    public CleanedFileDtos.Page list(Long projectId, Long departmentId, Status status, LocalDate from, LocalDate to,
                                     String search, int page) {
        requireSuper();
        if (page < 0 || page > 100000 || (from != null && to != null && from.isAfter(to))) bad("Invalid page or date range");
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        if (term.length() > 250) bad("Search must not exceed 250 characters");
        Specification<CleanedFile> spec = (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (projectId != null) predicates.add(cb.equal(root.get("project").get("id"), projectId));
            if (departmentId != null) predicates.add(cb.equal(root.get("department").get("id"), departmentId));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("cleanedOn"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("cleanedOn"), to));
            if (!term.isEmpty()) {
                String pattern = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("title")), pattern, '\\'),
                        cb.like(cb.lower(root.get("originalFilename")), pattern, '\\'),
                        cb.like(cb.lower(root.get("sourceReference")), pattern, '\\')));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var result = files.findAll(spec, PageRequest.of(page, 30, Sort.by("id").descending()));
        return new CleanedFileDtos.Page(result.getContent().stream().map(this::row).toList(), result.getTotalElements(),
                page, result.getTotalPages(), count(spec, Status.READY), count(spec, Status.IN_PROGRESS), count(spec, Status.COMPLETED));
    }

    @Transactional
    public CleanedFileDtos.Row upload(Long projectId, Long departmentId, CleanedFileDtos.Metadata metadata, MultipartFile file) {
        requireSuper(); validate(metadata);
        var project = projects.findWithMembersById(projectId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        var department = departments.findById(departmentId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found"));
        if (!department.isActive() || department.getProject() == null || !projectId.equals(department.getProject().getId())
                || !Objects.equals(project.getTeam() == null ? null : project.getTeam().getId(), department.getTeam() == null ? null : department.getTeam().getId())) {
            bad("Choose an active department belonging to the selected project");
        }
        if (file == null || file.isEmpty()) bad("File is required");
        if (file.getSize() > maxBytes) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Cleaned files must be at most " + maxBytes / 1048576 + " MB each");
        String name = Objects.toString(file.getOriginalFilename(), "file").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (name.isBlank() || name.length() > 250) bad("Invalid filename");
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension)) bad("Unsupported cleaned file format");
        var actor = users.findById(TenantContext.getUserId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        quota.chargeOrThrow(actor.getId(), file.getSize());
        Path stored = null;
        try {
            Files.createDirectories(directory);
            Path root = directory.toRealPath();
            String key = UUID.randomUUID() + "." + extension;
            stored = root.resolve(key);
            Path rollbackFile = stored;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) remove(rollbackFile);
                }
            });
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied;
            try (var input = new DigestInputStream(file.getInputStream(), digest)) { copied = Files.copy(input, stored); }
            if (copied != file.getSize() || copied > maxBytes) bad("File size does not match the upload");
            try (var input = Files.newInputStream(stored)) {
                if (!MIME.contains(TIKA.detect(input))) bad("File content is not a supported data or document format");
            }
            var entity = new CleanedFile();
            entity.setProject(project); entity.setDepartment(department); entity.setOriginalFilename(name);
            entity.setStorageKey(key); entity.setSizeBytes(copied); entity.setSha256(HexFormat.of().formatHex(digest.digest()));
            entity.setUploadedBy(actor.getUsername()); apply(entity, metadata);
            return row(files.saveAndFlush(entity));
        } catch (IOException | NoSuchAlgorithmException ex) {
            if (stored != null) remove(stored);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store file. Please retry.");
        }
    }

    @Transactional
    public CleanedFileDtos.Row update(Long id, CleanedFileDtos.Update update) {
        requireSuper(); validate(update.metadata());
        var entity = find(id);
        if (entity.getVersion() != update.version()) throw new ResponseStatusException(HttpStatus.CONFLICT, "File changed. Refresh and try again.");
        apply(entity, update.metadata());
        if (entity.getStatus() != update.status()) {
            Instant now = Instant.now();
            if (update.status() != Status.READY && entity.getStartedAt() == null) entity.setStartedAt(now);
            entity.setCompletedAt(update.status() == Status.COMPLETED ? now : null);
            if (update.status() == Status.READY) entity.setStartedAt(null);
            entity.setStatus(update.status());
        }
        entity.setUpdatedAt(Instant.now());
        try { return row(files.saveAndFlush(entity)); }
        catch (ObjectOptimisticLockingFailureException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "File changed. Refresh and try again."); }
    }

    public record Download(Resource resource, String filename, long size) {}
    public Download download(Long id) {
        requireSuper(); var entity = find(id);
        try {
            Path root = directory.toRealPath();
            Path path = root.resolve(entity.getStorageKey()).normalize();
            if (!path.startsWith(root) || Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !path.toRealPath().startsWith(root)) throw new IOException("Missing file");
            return new Download(new UrlResource(path.toUri()), entity.getOriginalFilename(), entity.getSizeBytes());
        } catch (IOException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stored file is unavailable"); }
    }

    private long count(Specification<CleanedFile> spec, Status status) {
        return files.count(spec.and((root, query, cb) -> cb.equal(root.get("status"), status)));
    }
    private CleanedFile find(Long id) { return files.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found")); }
    private static void requireSuper() { if (!TenantContext.isSuperAdmin() || TenantContext.isImpersonating()) throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
    private static void bad(String reason) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
    private static void remove(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException ex) { org.slf4j.LoggerFactory.getLogger(CleanedFileService.class).warn("Could not remove rolled-back cleaned file {}", path.getFileName()); }
    }
    private static void validate(CleanedFileDtos.Metadata metadata) {
        if (metadata.cleanedOn().isAfter(LocalDate.now(ZoneId.of("Africa/Cairo")))) bad("Cleaning date cannot be in the future");
        if (metadata.dueOn() != null && metadata.dueOn().isBefore(metadata.cleanedOn())) bad("Due date cannot precede cleaning date");
    }
    private static void apply(CleanedFile entity, CleanedFileDtos.Metadata metadata) {
        entity.setTitle(metadata.title().trim()); entity.setCleanedOn(metadata.cleanedOn()); entity.setDueOn(metadata.dueOn());
        entity.setSourceReference(metadata.sourceReference()); entity.setNotes(metadata.notes());
    }
    private CleanedFileDtos.Row row(CleanedFile entity) {
        var p = entity.getProject(); var d = entity.getDepartment(); var team = p.getTeam();
        return new CleanedFileDtos.Row(entity.getId(), entity.getTitle(), entity.getOriginalFilename(), entity.getSizeBytes(), entity.getSha256(),
                team == null ? null : team.getId(), team == null ? null : team.getName(), p.getId(), p.getName(), d.getId(), d.getName(),
                entity.getCleanedOn(), entity.getDueOn(), entity.getSourceReference(), entity.getNotes(), entity.getStatus(), entity.getUploadedBy(),
                entity.getUploadedAt(), entity.getUpdatedAt(), entity.getStartedAt(), entity.getCompletedAt(), entity.getVersion());
    }
}
