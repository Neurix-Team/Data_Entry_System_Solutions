package com.dataentry.service;

import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key sliding-window limiter kept in this process's heap.  Fine when there is only one
 * backend instance.  Wired up by {@code RateLimiterConfig} when {@code app.security.login-rate.storage=memory}.
 */
public class InMemoryLoginRateLimiter implements LoginRateLimiter {

    private static final int MAX_TRACKED_KEYS = 10_000;

    private final int maxAttempts;
    private final Duration window;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public InMemoryLoginRateLimiter(
            @Value("${app.security.login-rate.max-attempts:10}") int maxAttempts,
            @Value("${app.security.login-rate.window-seconds:300}") long windowSeconds
    ) {
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    @Override
    public boolean tryAcquire(String key) {
        if (hits.size() > MAX_TRACKED_KEYS) hits.clear();
        Instant now = Instant.now();
        Instant cutoff = now.minus(window);
        Deque<Instant> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) q.pollFirst();
            if (q.size() >= maxAttempts) return false;
            q.addLast(now);
            return true;
        }
    }

    @Override
    public void reset(String key) {
        hits.remove(key);
    }
}
