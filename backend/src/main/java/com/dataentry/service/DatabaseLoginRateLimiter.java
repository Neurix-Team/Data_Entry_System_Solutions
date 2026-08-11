package com.dataentry.service;

import com.dataentry.model.LoginAttempt;
import com.dataentry.repository.LoginAttemptRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;


public class DatabaseLoginRateLimiter implements LoginRateLimiter {

    private final LoginAttemptRepository repository;
    private final int maxAttempts;
    private final Duration window;
    private final AtomicLong sinceLastPrune = new AtomicLong(0);

    public DatabaseLoginRateLimiter(
            LoginAttemptRepository repository,
            @Value("${app.security.login-rate.max-attempts:10}") int maxAttempts,
            @Value("${app.security.login-rate.window-seconds:300}") long windowSeconds
    ) {
        this.repository = repository;
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    @Override
    @Transactional
    public boolean tryAcquire(String key) {
        Instant now = Instant.now();
        Instant windowStart = now.minus(window);
        long existing = repository.countByAttemptKeyAndAttemptedAtGreaterThanEqual(key, windowStart);
        if (existing >= maxAttempts) return false;
        repository.save(LoginAttempt.builder().attemptKey(key).attemptedAt(now).build());
        maybePrune(now);
        return true;
    }

    @Override
    @Transactional
    public void reset(String key) {
        repository.deleteByKey(key);
    }

    /** Amortized cleanup — roughly once per 500 successful acquires we sweep rows older than
     *  the window so the table stays roughly bounded to (unique_keys * max_attempts) rows. */
    private void maybePrune(Instant now) {
        if (sinceLastPrune.incrementAndGet() < 500) return;
        sinceLastPrune.set(0);
        try {
            repository.deleteOlderThan(now.minus(window).minusSeconds(60));
        } catch (Exception ignored) {
            // Prune is best-effort — a failure here must not block a legitimate login.
        }
    }
}
