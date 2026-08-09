package com.dataentry.service;

/**
 * Sliding-window rate limiter for the login endpoint.  Implementations live in
 * {@code InMemoryLoginRateLimiter} (default) and {@code DatabaseLoginRateLimiter}
 * (shared across processes for multi-instance deployments).  Which one is wired up is decided
 * by {@link com.dataentry.config.RateLimiterConfig} from {@code app.security.login-rate.storage}.
 */
public interface LoginRateLimiter {

    /** Returns true if this attempt is allowed; false if the caller is over the limit. */
    boolean tryAcquire(String key);

    /** Clear tracked attempts for the key — call on successful login. */
    void reset(String key);
}
