package com.dataentry.service;

import com.dataentry.dto.TicketDtos;
import com.dataentry.dto.UploadSessionDtos;
import com.dataentry.model.Role;
import com.dataentry.model.UploadSession;
import com.dataentry.model.UploadTarget;
import com.dataentry.model.User;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.UploadSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the on-disk half of {@link ChunkedUploadService}: chunks landing out of order
 * into a pre-sized payload, the marker bookkeeping the status/complete calls read, and
 * the cleanup rules around finalize. Repositories and the attach step are mocked so the
 * test sees exactly the bytes the service would hand to {@link TicketDocumentService}.
 */
class ChunkedUploadServiceTest {

    /** The service clamps chunk size to at least 256 KB, so the test speaks that size. */
    private static final int CHUNK = 256 * 1024;

    @TempDir Path tmp;

    private final Map<String, UploadSession> store = new HashMap<>();
    private final User owner = User.builder().id(7L).username("agent").role(Role.USER).active(true).build();
    private final User stranger = User.builder().id(8L).username("other").role(Role.USER).active(true).build();

    private UploadSessionRepository sessions;
    private TicketDocumentService documents;
    private ChunkedUploadService service;

    @BeforeEach
    void setUp() {
        sessions = mock(UploadSessionRepository.class);
        when(sessions.save(any(UploadSession.class))).thenAnswer(inv -> {
            UploadSession s = inv.getArgument(0);
            store.put(s.getId(), s);
            return s;
        });
        when(sessions.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.<String>getArgument(0))));
        doAnswer(inv -> {
            store.remove(inv.<UploadSession>getArgument(0).getId());
            return null;
        }).when(sessions).delete(any(UploadSession.class));

        documents = mock(TicketDocumentService.class);
        service = new ChunkedUploadService(
                sessions,
                mock(DepartmentRepository.class),
                mock(ProjectFolderService.class),
                documents,
                new UploadQuotaService(Long.MAX_VALUE),
                tmp.resolve("incoming").toString(),
                CHUNK,
                500L * 1024 * 1024,
                24);
    }

    private static byte[] pseudoRandom(int n) {
        byte[] b = new byte[n];
        new Random(42).nextBytes(b);
        return b;
    }

    private UploadSessionDtos.SessionResponse open(byte[] payload) {
        return service.create(owner, new UploadSessionDtos.CreateRequest(
                "book.pdf", payload.length, "application/pdf",
                UploadTarget.TICKET_DOCUMENT, null, null, 42L, "Book"));
    }

    private void send(String id, int index, byte[] payload) {
        int from = index * CHUNK;
        int to = Math.min(payload.length, from + CHUNK);
        service.writeChunk(owner, id, index, new ByteArrayInputStream(Arrays.copyOfRange(payload, from, to)));
    }

    @Test
    void chunksArrivingOutOfOrderAssembleTheOriginalBytes() {
        byte[] payload = pseudoRandom(2 * CHUNK + 1000);
        UploadSessionDtos.SessionResponse s = open(payload);
        assertEquals(3, s.totalChunks());
        assertEquals(CHUNK, s.chunkBytes());
        assertTrue(s.received().isEmpty());

        send(s.id(), 2, payload);
        send(s.id(), 0, payload);
        send(s.id(), 1, payload);
        assertEquals(List.of(0, 1, 2), service.status(owner, s.id()).received());

        ArgumentCaptor<TicketDocumentService.IncomingFile> captor =
                ArgumentCaptor.forClass(TicketDocumentService.IncomingFile.class);
        when(documents.attach(eq(42L), eq("Book"), captor.capture(), eq(owner), eq(false)))
                .thenAnswer(inv -> {
                    TicketDocumentService.IncomingFile in = inv.getArgument(2);
                    // The real attach renames the payload away — check the bytes before that.
                    assertArrayEquals(payload, Files.readAllBytes(in.path()));
                    return new TicketDtos.DocumentResponse(
                            1L, "Book", "book.pdf", "application/pdf", in.size(), Instant.now());
                });

        UploadSessionDtos.CompleteResponse done = service.complete(owner, s.id());
        assertEquals(UploadTarget.TICKET_DOCUMENT, done.target());
        assertNotNull(done.document());
        assertEquals(payload.length, done.document().sizeBytes());
        assertEquals("book.pdf", captor.getValue().originalFilename());
        assertFalse(store.containsKey(s.id()), "session row is deleted after completion");
        assertFalse(Files.exists(tmp.resolve("incoming").resolve(s.id())), "session directory is reclaimed");
    }

    @Test
    void shortChunkIsRejectedAndTheSessionStaysResumable() {
        byte[] payload = pseudoRandom(CHUNK + 10);
        UploadSessionDtos.SessionResponse s = open(payload);

        ResponseStatusException tooShort = assertThrows(ResponseStatusException.class,
                () -> service.writeChunk(owner, s.id(), 0, new ByteArrayInputStream(new byte[100])));
        assertEquals(HttpStatus.BAD_REQUEST, tooShort.getStatusCode());
        assertTrue(service.status(owner, s.id()).received().isEmpty(), "a bad chunk leaves no marker");

        send(s.id(), 1, payload);
        ResponseStatusException incomplete = assertThrows(ResponseStatusException.class,
                () -> service.complete(owner, s.id()));
        assertEquals(HttpStatus.BAD_REQUEST, incomplete.getStatusCode());
        assertTrue(store.containsKey(s.id()), "an incomplete upload keeps its session for a resume");

        send(s.id(), 0, payload);
        assertEquals(List.of(0, 1), service.status(owner, s.id()).received());
    }

    @Test
    void rejectedFinalizeFreesTheSession() {
        byte[] payload = pseudoRandom(10);
        UploadSessionDtos.SessionResponse s = open(payload);
        send(s.id(), 0, payload);
        when(documents.attach(anyLong(), any(), any(), any(), anyBoolean()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "duplicate"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.complete(owner, s.id()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertFalse(store.containsKey(s.id()));
        assertFalse(Files.exists(tmp.resolve("incoming").resolve(s.id())));
    }

    @Test
    void anotherUserCannotSeeTheSession() {
        UploadSessionDtos.SessionResponse s = open(pseudoRandom(10));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.status(stranger, s.id()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void oversizedFileIsRefusedBeforeAnyChunk() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(owner, new UploadSessionDtos.CreateRequest(
                        "huge.pdf", 600L * 1024 * 1024, null,
                        UploadTarget.TICKET_DOCUMENT, null, null, 42L, null)));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, ex.getStatusCode());
        assertTrue(store.isEmpty());
    }
}
