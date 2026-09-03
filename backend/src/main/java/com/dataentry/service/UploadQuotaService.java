package com.dataentry.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class UploadQuotaService {

    private record Hit(Instant at, long bytes) {}

    private final long dailyBytes;
    private final Map<Long, Deque<Hit>> byUser = new ConcurrentHashMap<>();

    public UploadQuotaService(
            @Value("${app.uploads.per-user-daily-bytes:524288000}") long dailyBytes) {
        this.dailyBytes = dailyBytes;
    }

    /** Record {@code bytes} against the user's rolling 24h window, or refuse with 413. */
    public void chargeOrThrow(Long userId, long bytes) {
        if (userId == null || bytes <= 0) return;
        Instant now = Instant.now();
        Deque<Hit> q = byUser.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            if (usedSince(q, now.minusSeconds(24 * 3600)) + bytes > dailyBytes) {
                throw quotaExceeded();
            }
            q.addLast(new Hit(now, bytes));
        }
    }

    /**
     * Same check as {@link #chargeOrThrow} without recording anything. The chunked upload
     * flow calls this when a session opens so a user who is already over quota is told
     * immediately, rather than after pushing a whole book through the wire; the real
     * charge still happens once on finalize.
     */
    public void assertRoom(Long userId, long bytes) {
        if (userId == null || bytes <= 0) return;
        Deque<Hit> q = byUser.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            if (usedSince(q, Instant.now().minusSeconds(24 * 3600)) + bytes > dailyBytes) {
                throw quotaExceeded();
            }
        }
    }

    /** Drops hits that fell out of the window, then sums what's left. Caller holds the lock. */
    private static long usedSince(Deque<Hit> q, Instant cutoff) {
        while (!q.isEmpty() && q.peekFirst().at().isBefore(cutoff)) q.pollFirst();
        return q.stream().mapToLong(Hit::bytes).sum();
    }

    private ResponseStatusException quotaExceeded() {
        long mb = dailyBytes / (1024 * 1024);
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "Daily upload quota (" + mb + " MB) exceeded — try again tomorrow.");
    }
}
