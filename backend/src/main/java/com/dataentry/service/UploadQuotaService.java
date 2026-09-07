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

    public void assertRoom(Long userId, long bytes) {
        if (userId == null || bytes <= 0) return;
        Deque<Hit> q = byUser.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            if (usedSince(q, Instant.now().minusSeconds(24 * 3600)) + bytes > dailyBytes) {
                throw quotaExceeded();
            }
        }
    }

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
