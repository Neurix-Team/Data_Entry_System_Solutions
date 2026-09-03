package com.dataentry.service;

import com.dataentry.dto.TicketDtos;
import com.dataentry.dto.UploadSessionDtos;
import com.dataentry.model.Department;
import com.dataentry.model.UploadSession;
import com.dataentry.model.UploadTarget;
import com.dataentry.model.User;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.UploadSessionRepository;
import com.dataentry.security.TenantGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Chunked, parallel, resumable uploads.
 *
 * <p>Why this exists: a scanned book is hundreds of megabytes, and a single multipart
 * request has to push all of it through one TCP stream, through two reverse proxies,
 * and only then does the server start copying and hashing. The client sees a bar that
 * crawls, and any hiccup throws the whole transfer away. Here the browser opens a
 * session, sends fixed-size chunks with several requests in flight at once (which
 * fills the pipe on high-latency links), retries only the chunk that failed, and asks
 * the server to finalize once everything landed.
 *
 * <p>On the server each chunk is written straight into its slot of a pre-sized payload
 * file with a positional write, so there is nothing to concatenate later. A marker
 * file per chunk records receipt without any row contention. Finalize verifies every
 * marker, then hands the payload to the same validate-hash-dedupe-attach path the
 * multipart endpoint uses; because the incoming directory sits inside the attachments
 * volume, the last step is a rename, not a copy.
 *
 * <p>Deliberately not one big {@code @Transactional}: streaming a chunk must never hold
 * a pooled DB connection, or six parallel chunks per user would starve everyone else.
 */
@Service
public class ChunkedUploadService {

    private static final Logger log = LoggerFactory.getLogger(ChunkedUploadService.class);

    private static final String PAYLOAD_FILE = "payload.bin";
    private static final String CHUNKS_DIR = "chunks";
    private static final String MARKER_SUFFIX = ".ok";
    private static final int COPY_BUFFER = 256 * 1024;
    private static final Pattern UUID_SHAPE =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final UploadSessionRepository sessions;
    private final DepartmentRepository departmentRepository;
    private final ProjectFolderService folders;
    private final TicketDocumentService documents;
    private final UploadQuotaService quota;
    private final Path incomingDir;
    private final int chunkBytes;
    private final long maxFileBytes;
    private final Duration sessionTtl;

    /** Serialises concurrent finalize calls for the same session (double-click, retry). */
    private final Map<String, Object> completionLocks = new ConcurrentHashMap<>();

    public ChunkedUploadService(UploadSessionRepository sessions,
                                DepartmentRepository departmentRepository,
                                ProjectFolderService folders,
                                TicketDocumentService documents,
                                UploadQuotaService quota,
                                @Value("${app.uploads.incoming-dir:./data/attachments/.incoming}") String incomingDir,
                                @Value("${app.uploads.chunk-bytes:8388608}") int chunkBytes,
                                @Value("${app.attachments.max-file-bytes:524288000}") long maxFileBytes,
                                @Value("${app.uploads.session-ttl-hours:24}") long sessionTtlHours) {
        this.sessions = sessions;
        this.departmentRepository = departmentRepository;
        this.folders = folders;
        this.documents = documents;
        this.quota = quota;
        this.incomingDir = Paths.get(incomingDir).toAbsolutePath().normalize();
        this.chunkBytes = Math.max(256 * 1024, chunkBytes);
        this.maxFileBytes = maxFileBytes;
        this.sessionTtl = Duration.ofHours(Math.max(1, sessionTtlHours));
        try {
            Files.createDirectories(this.incomingDir);
        } catch (IOException e) {
            log.warn("Could not create incoming upload directory {}: {}", this.incomingDir, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ open

    @Transactional
    public UploadSessionDtos.SessionResponse create(User user, UploadSessionDtos.CreateRequest req) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        long size = req.size();
        if (size <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }
        if (size > maxFileBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "File exceeds " + (maxFileBytes / (1024 * 1024)) + " MB limit");
        }
        String filename = TicketDocumentService.sanitiseFilename(req.filename());
        documents.assertExtensionAllowed(filename);
        quota.assertRoom(user.getId(), size);

        Long projectId = null;
        Long departmentId = null;
        Long ticketId = null;
        switch (req.target()) {
            case QUICK_UPLOAD -> {
                if (req.projectId() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId is required");
                }
                folders.assertCanUploadTo(req.projectId(), user);
                if (req.departmentId() != null) {
                    assertDepartmentInProject(req.projectId(), req.departmentId());
                }
                projectId = req.projectId();
                departmentId = req.departmentId();
            }
            case TICKET_DOCUMENT -> {
                if (req.ticketId() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ticketId is required");
                }
                documents.assertCanAttach(req.ticketId(), user, user.isAdminLike());
                ticketId = req.ticketId();
            }
        }

        int totalChunks = (int) ((size + chunkBytes - 1) / chunkBytes);
        String id = UUID.randomUUID().toString();
        Path dir = sessionDir(id);
        try {
            Files.createDirectories(dir.resolve(CHUNKS_DIR));
            // Pre-size the payload so positional chunk writes never race on extending the
            // file, and so a finished upload is exactly the declared length by construction.
            try (RandomAccessFile raf = new RandomAccessFile(dir.resolve(PAYLOAD_FILE).toFile(), "rw")) {
                raf.setLength(size);
            }
        } catch (IOException e) {
            deleteRecursively(dir);
            log.warn("Could not prepare upload session {}: {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not prepare upload storage");
        }

        String title = req.title() == null ? null : req.title().trim();
        UploadSession s = UploadSession.builder()
                .id(id)
                .ownerId(user.getId())
                .target(req.target())
                .projectId(projectId)
                .departmentId(departmentId)
                .ticketId(ticketId)
                .title(title == null || title.isEmpty() ? null : title)
                .originalFilename(filename)
                .clientContentType(req.contentType())
                .declaredSize(size)
                .chunkBytes(chunkBytes)
                .totalChunks(totalChunks)
                .expiresAt(Instant.now().plus(sessionTtl))
                .build();
        sessions.save(s);
        return toResponse(s, List.of());
    }

    // ------------------------------------------------------------------ feed

    /**
     * Stream one chunk's bytes into its slot. Reads the request body to EOF and insists
     * on exactly the expected length — a short or long body means the client and server
     * disagree about the layout, and the chunk is left unmarked so it gets re-sent.
     */
    public UploadSessionDtos.ChunkAck writeChunk(User user, String sessionId, int index, InputStream body) {
        UploadSession s = loadOwned(user, sessionId);
        if (index < 0 || index >= s.getTotalChunks()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chunk index out of range");
        }
        long offset = (long) index * s.getChunkBytes();
        long expected = Math.min(s.getChunkBytes(), s.getDeclaredSize() - offset);
        Path payload = sessionDir(sessionId).resolve(PAYLOAD_FILE);

        long written = 0;
        try (FileChannel channel = FileChannel.open(payload, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[COPY_BUFFER];
            int n;
            while ((n = body.read(buf)) != -1) {
                if (written + n > expected) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Chunk " + index + " is larger than expected");
                }
                ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
                long pos = offset + written;
                while (bb.hasRemaining()) {
                    pos += channel.write(bb, pos);
                }
                written += n;
            }
        } catch (NoSuchFileException e) {
            throw new ResponseStatusException(HttpStatus.GONE, "Upload session is no longer open");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read chunk");
        }
        if (written != expected) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Chunk " + index + " length mismatch: expected " + expected + " bytes, got " + written);
        }
        markReceived(sessionId, index);
        return new UploadSessionDtos.ChunkAck(index, written);
    }

    public UploadSessionDtos.SessionResponse status(User user, String sessionId) {
        UploadSession s = loadOwned(user, sessionId);
        return toResponse(s, new ArrayList<>(receivedChunks(sessionId)));
    }

    // ------------------------------------------------------------------ finish

    /**
     * Turn a fully-received payload into a ticket attachment. Validation errors (wrong
     * type, duplicate, over quota) are terminal: the session is discarded and the client
     * gets the reason. A transient server failure keeps the session so the client can
     * simply call complete again instead of re-sending the file.
     */
    public UploadSessionDtos.CompleteResponse complete(User user, String sessionId) {
        UploadSession s = loadOwned(user, sessionId);
        Object lock = completionLocks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            try {
                // Layout problems keep the session: the client can ask for status and
                // resend only what's missing instead of starting the whole book over.
                TreeSet<Integer> received = receivedChunks(sessionId);
                if (received.size() < s.getTotalChunks()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Upload incomplete: " + (s.getTotalChunks() - received.size())
                                    + " of " + s.getTotalChunks() + " chunks missing");
                }
                Path payload = sessionDir(sessionId).resolve(PAYLOAD_FILE);
                long actual;
                try {
                    actual = Files.size(payload);
                } catch (IOException e) {
                    throw new ResponseStatusException(HttpStatus.GONE, "Upload session is no longer open");
                }
                if (actual != s.getDeclaredSize()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Upload size mismatch: expected " + s.getDeclaredSize() + " bytes, got " + actual);
                }

                TicketDocumentService.IncomingFile file =
                        new TicketDocumentService.IncomingFile(payload, s.getOriginalFilename(), actual);
                UploadSessionDtos.CompleteResponse result;
                try {
                    if (s.getTarget() == UploadTarget.QUICK_UPLOAD) {
                        TicketDtos.TicketResponse ticket = folders.createTicketAndAttach(
                                s.getProjectId(), s.getDepartmentId(), user, s.getTitle(), file);
                        result = new UploadSessionDtos.CompleteResponse(UploadTarget.QUICK_UPLOAD, ticket, null);
                    } else {
                        TicketDtos.DocumentResponse doc = documents.attach(
                                s.getTicketId(), s.getTitle(), file, user, user.isAdminLike());
                        result = new UploadSessionDtos.CompleteResponse(UploadTarget.TICKET_DOCUMENT, null, doc);
                    }
                } catch (ResponseStatusException e) {
                    // Content rejections (wrong type, duplicate, over quota) are terminal: free
                    // the disk now. A transient 5xx keeps the session so completing again works.
                    if (!e.getStatusCode().is5xxServerError()) {
                        discard(sessionId);
                    }
                    throw e;
                } catch (RuntimeException e) {
                    log.warn("Finalizing upload session {} failed: {}", sessionId, e.toString());
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Upload failed");
                }
                discard(sessionId);
                return result;
            } finally {
                completionLocks.remove(sessionId);
            }
        }
    }

    public void abort(User user, String sessionId) {
        loadOwned(user, sessionId);
        discard(sessionId);
    }

    // ------------------------------------------------------------------ housekeeping

    /**
     * Reclaims abandoned uploads: expired session rows (with their directories) and any
     * orphaned files in the incoming area older than the session TTL. The multipart
     * endpoint's temporary files also live here, so stale {@code .tmp}/{@code .part}
     * leftovers from a crashed request are swept by the same pass.
     */
    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT1H")
    public void sweepExpired() {
        Instant now = Instant.now();
        for (UploadSession s : sessions.findAllByExpiresAtBefore(now)) {
            log.info("Sweeping expired upload session {} ({} bytes)", s.getId(), s.getDeclaredSize());
            discard(s.getId());
        }
        FileTime cutoff = FileTime.from(now.minus(sessionTtl));
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(incomingDir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                try {
                    if (Files.getLastModifiedTime(entry).compareTo(cutoff) >= 0) continue;
                    if (Files.isDirectory(entry)) {
                        if (UUID_SHAPE.matcher(name).matches() && !sessions.existsById(name)) {
                            deleteRecursively(entry);
                        }
                    } else {
                        Files.deleteIfExists(entry);
                    }
                } catch (IOException e) {
                    log.debug("Could not inspect {} during upload sweep: {}", entry, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.debug("Upload sweep could not list {}: {}", incomingDir, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ internals

    private UploadSession loadOwned(User user, String sessionId) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (sessionId == null || !UUID_SHAPE.matcher(sessionId).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload session not found");
        }
        UploadSession s = sessions.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload session not found"));
        // 404 rather than 403 so a guessed id doesn't confirm that someone else's session exists.
        if (!s.getOwnerId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload session not found");
        }
        if (s.getExpiresAt().isBefore(Instant.now())) {
            discard(sessionId);
            throw new ResponseStatusException(HttpStatus.GONE, "Upload session expired");
        }
        return s;
    }

    private void assertDepartmentInProject(Long projectId, Long departmentId) {
        Department dept = departmentRepository.findById(departmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department not found"));
        TenantGuard.assertOwnership(dept);
        Long deptProjectId = dept.getProject() != null ? dept.getProject().getId() : null;
        if (deptProjectId == null || !deptProjectId.equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That department does not belong to this project.");
        }
    }

    private Path sessionDir(String sessionId) {
        Path dir = incomingDir.resolve(sessionId).normalize();
        if (!dir.getParent().equals(incomingDir)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload session not found");
        }
        return dir;
    }

    private void markReceived(String sessionId, int index) {
        Path marker = sessionDir(sessionId).resolve(CHUNKS_DIR).resolve(index + MARKER_SUFFIX);
        try {
            Files.createFile(marker);
        } catch (FileAlreadyExistsException ignored) {
            // A retried chunk overwrote identical bytes — still received.
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not record chunk");
        }
    }

    private TreeSet<Integer> receivedChunks(String sessionId) {
        TreeSet<Integer> out = new TreeSet<>();
        Path dir = sessionDir(sessionId).resolve(CHUNKS_DIR);
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> markers = Files.newDirectoryStream(dir, "*" + MARKER_SUFFIX)) {
            for (Path m : markers) {
                String name = m.getFileName().toString();
                try {
                    out.add(Integer.parseInt(name.substring(0, name.length() - MARKER_SUFFIX.length())));
                } catch (NumberFormatException ignored) {
                    // Not one of ours.
                }
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read upload state");
        }
        return out;
    }

    private void discard(String sessionId) {
        try {
            sessions.findById(sessionId).ifPresent(sessions::delete);
        } catch (RuntimeException e) {
            log.debug("Could not delete upload session row {}: {}", sessionId, e.getMessage());
        }
        if (UUID_SHAPE.matcher(sessionId).matches()) {
            deleteRecursively(incomingDir.resolve(sessionId));
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException e) {
            log.debug("Could not remove {}: {}", dir, e.getMessage());
        }
    }

    private static UploadSessionDtos.SessionResponse toResponse(UploadSession s, List<Integer> received) {
        return new UploadSessionDtos.SessionResponse(
                s.getId(), s.getOriginalFilename(), s.getDeclaredSize(),
                s.getChunkBytes(), s.getTotalChunks(), received, s.getExpiresAt());
    }
}
