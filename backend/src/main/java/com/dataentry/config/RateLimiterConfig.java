package com.dataentry.config;

import com.dataentry.repository.LoginAttemptRepository;
import com.dataentry.service.DatabaseLoginRateLimiter;
import com.dataentry.service.InMemoryLoginRateLimiter;
import com.dataentry.service.LoginRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the {@link LoginRateLimiter} implementation from configuration.
 *   memory   → per-process counters (default; correct when there's a single backend instance)
 *   database → shared counters via the login_attempts table (correct when running >1 replica)
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    public LoginRateLimiter loginRateLimiter(
            @Value("${app.security.login-rate.storage:memory}") String storage,
            @Value("${app.security.login-rate.max-attempts:10}") int maxAttempts,
            @Value("${app.security.login-rate.window-seconds:300}") long windowSeconds,
            LoginAttemptRepository loginAttemptRepository
    ) {
        if ("database".equalsIgnoreCase(storage)) {
            return new DatabaseLoginRateLimiter(loginAttemptRepository, maxAttempts, windowSeconds);
        }
        return new InMemoryLoginRateLimiter(maxAttempts, windowSeconds);
    }
}
