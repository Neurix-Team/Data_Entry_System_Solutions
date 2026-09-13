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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final boolean postgres;
    private final int maxAttempts;
    private final Duration window;
    private final AtomicLong sinceLastPrune = new AtomicLong(0);

    public DatabaseLoginRateLimiter(
            LoginAttemptRepository repository,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            @Value("${app.security.login-rate.max-attempts:10}") int maxAttempts,
            @Value("${app.security.login-rate.window-seconds:300}") long windowSeconds
    ) {
        this.repository = repository;
        this.jdbc = jdbc;
        try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            this.postgres = connection.getMetaData().getDatabaseProductName().equals("PostgreSQL");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Cannot initialize login limiter", e);
        }
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    @Override
    @Transactional
    public synchronized boolean tryAcquire(String key) {
        lockKey(key);
        Instant now = Instant.now();
        Instant windowStart = now.minus(window);
        long existing = repository.countByAttemptKeyAndAttemptedAtGreaterThanEqual(key, windowStart);
        if (existing >= (key.startsWith("network:") ? maxAttempts * 10L : maxAttempts)) return false;
        repository.save(LoginAttempt.builder().attemptKey(key).attemptedAt(now).build());
        maybePrune(now);
        return true;
    }

    @Override
    @Transactional
    public void reset(String key) {
        lockKey(key);
        repository.deleteByKey(key);
    }

     
    private void lockKey(String key) {
        if (!postgres) return; // H2 is used only by local tests.
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long lockId = java.nio.ByteBuffer.wrap(digest).getLong();
            jdbc.query("select pg_advisory_xact_lock(?)",
                    (org.springframework.jdbc.core.ResultSetExtractor<Void>) result -> null, lockId);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void maybePrune(Instant now) {
        if (sinceLastPrune.incrementAndGet() < 500) return;
        sinceLastPrune.set(0);
        try {
            repository.deleteOlderThan(now.minus(window).minusSeconds(60));
        } catch (Exception ignored) {
             
        }
    }
}
