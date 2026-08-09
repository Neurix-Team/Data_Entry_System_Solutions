package com.dataentry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * Exclude Spring Boot's default in-memory user store — we authenticate everyone via our own
 * {@code JwtAuthFilter} + {@code UserRepository}.  Leaving the auto-config on causes Spring
 * to print a random "Using generated security password: ..." at boot and register a bogus
 * `user / <uuid>` credential that has no place in a JWT-based app.
 */
@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
public class DataEntryApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataEntryApplication.class, args);
    }
}
