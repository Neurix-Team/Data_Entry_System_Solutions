package com.dataentry.service;

import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    public synchronized boolean tryAcquire(String key) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(window);
        hits.values().removeIf(q -> q.isEmpty() || q.peekLast().isBefore(cutoff));
        if (!hits.containsKey(key) && hits.size() >= MAX_TRACKED_KEYS) return false;
        Deque<Instant> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) q.pollFirst();
            if (q.size() >= (key.startsWith("network:") ? maxAttempts * 10L : maxAttempts)) return false;
            q.addLast(now);
            return true;
        }
    }

    @Override
    public synchronized void reset(String key) {
        hits.remove(key);
    }
}
