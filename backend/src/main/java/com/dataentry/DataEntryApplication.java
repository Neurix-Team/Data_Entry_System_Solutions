package com.dataentry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Exclude Spring Boot's default in-memory user store — we authenticate everyone via our own
 * {@code JwtAuthFilter} + {@code UserRepository}.  Leaving the auto-config on causes Spring
 * to print a random "Using generated security password: ..." at boot and register a bogus
 * `user / <uuid>` credential that has no place in a JWT-based app.
 *
 * <p>{@code @EnableScheduling} powers the hourly sweep of abandoned chunked-upload
 * sessions in {@code ChunkedUploadService}.
 */
@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
@EnableScheduling
public class DataEntryApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataEntryApplication.class, args);
    }
}
