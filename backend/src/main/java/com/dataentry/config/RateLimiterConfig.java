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
 *   memory   → per-process counters for isolated development only
 *   database → durable shared counters via the login_attempts table (the default)
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    public LoginRateLimiter loginRateLimiter(
            @Value("${app.security.login-rate.storage:database}") String storage,
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
