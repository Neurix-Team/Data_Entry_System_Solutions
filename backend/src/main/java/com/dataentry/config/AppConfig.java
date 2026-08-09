package com.dataentry.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppConfig {

    /**
     * Inject Clock everywhere instead of calling LocalDate.now() / Instant.now() directly.
     * Tests can then swap it for Clock.fixed(...) to make time-based logic deterministic.
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
