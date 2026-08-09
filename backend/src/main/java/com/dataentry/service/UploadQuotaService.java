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

/**
 * Tracks per-user upload bytes in a rolling 24h window and rejects requests that would push
 * the user over the configured cap.  Kept in-memory — quotas reset on process restart, which
 * is acceptable because they exist to smooth out spikes, not to enforce billing.
 */
@Component
public class UploadQuotaService {

    private record Hit(Instant at, long bytes) {}

    private final long dailyBytes;
    private final Map<Long, Deque<Hit>> byUser = new ConcurrentHashMap<>();

    public UploadQuotaService(
            @Value("${app.uploads.per-user-daily-bytes:524288000}") long dailyBytes) {
        this.dailyBytes = dailyBytes;
    }

    /**
     * Records an upload attempt. Throws 413 (Payload Too Large) if the user would exceed the
     * rolling-24h byte cap.  Call before you actually start processing the file so we don't
     * do work we're about to discard.
     */
    public void chargeOrThrow(Long userId, long bytes) {
        if (userId == null || bytes <= 0) return;
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(24 * 3600);
        Deque<Hit> q = byUser.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().at().isBefore(cutoff)) q.pollFirst();
            long used = q.stream().mapToLong(Hit::bytes).sum();
            if (used + bytes > dailyBytes) {
                long mb = dailyBytes / (1024 * 1024);
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Daily upload quota (" + mb + " MB) exceeded — try again tomorrow.");
            }
            q.addLast(new Hit(now, bytes));
        }
    }
}
