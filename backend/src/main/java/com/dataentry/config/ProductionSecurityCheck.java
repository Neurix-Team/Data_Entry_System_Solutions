package com.dataentry.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@Profile("docker")
public class ProductionSecurityCheck {

    private static final Logger log = LoggerFactory.getLogger(ProductionSecurityCheck.class);

    static final String DEFAULT_ADMIN_PW      = "admin123";
    static final String DEFAULT_SUPERADMIN_PW = "superadmin123";

    static final Set<String> KNOWN_PLACEHOLDER_JWT_SECRETS = Set.of(
            "change-me-in-production-a-very-long-random-secret-key-min-32-chars",
            "local-dev-secret-please-rotate-me-with-a-real-random-string-32chars-min"
    );

    static final int MIN_JWT_SECRET_LENGTH = 32;

    private final String adminPassword;
    private final String superAdminPassword;
    private final String jwtSecret;
    private final String corsOrigins;

    public ProductionSecurityCheck(
            @Value("${app.seed.admin-password:}") String adminPassword,
            @Value("${app.seed.superadmin-password:}") String superAdminPassword,
            @Value("${app.jwt.secret:}") String jwtSecret,
            @Value("${app.cors.allowed-origins:}") String corsOrigins) {
        this.adminPassword = adminPassword;
        this.superAdminPassword = superAdminPassword;
        this.jwtSecret = jwtSecret;
        this.corsOrigins = corsOrigins;
    }

    @PostConstruct
    void check() {
        List<String> problems = collectProblems();
        if (problems.isEmpty()) {
            log.info("Production security check passed — no built-in defaults detected.");
            return;
        }
        throw new IllegalStateException(formatMessage(problems));
    }

    List<String> collectProblems() {
        List<String> problems = new ArrayList<>();

        if (DEFAULT_ADMIN_PW.equals(adminPassword)) {
            problems.add("APP_SEED_ADMIN_PASSWORD is still the built-in default 'admin123'. "
                    + "Rotate it before exposing this instance.");
        }
        if (DEFAULT_SUPERADMIN_PW.equals(superAdminPassword)) {
            problems.add("APP_SEED_SUPERADMIN_PASSWORD is still the built-in default 'superadmin123'. "
                    + "Rotate it before exposing this instance.");
        }
        if (jwtSecret == null || jwtSecret.isBlank()) {
            problems.add("JWT_SECRET is empty. Generate one with `openssl rand -base64 48`.");
        } else if (jwtSecret.length() < MIN_JWT_SECRET_LENGTH) {
            problems.add("JWT_SECRET must be at least " + MIN_JWT_SECRET_LENGTH
                    + " characters — HMAC-SHA256 strength depends on it.");
        } else if (KNOWN_PLACEHOLDER_JWT_SECRETS.contains(jwtSecret)) {
            problems.add("JWT_SECRET is a well-known placeholder value that has appeared in git "
                    + "history and in application.yml. Anyone can forge tokens against it. Rotate now.");
        }
        if (corsOrigins == null || corsOrigins.isBlank()) {
            problems.add("APP_CORS_ALLOWED_ORIGINS must be set. The API sends credentialed "
                    + "cookies and refuses '*' at boot, so an explicit allowlist is required.");
        }

        return problems;
    }

    static String formatMessage(List<String> problems) {
        StringBuilder msg = new StringBuilder(256)
                .append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("============================================================").append(System.lineSeparator())
                .append("REFUSING TO BOOT — production security check failed:").append(System.lineSeparator())
                .append(System.lineSeparator());
        for (String p : problems) {
            msg.append("  • ").append(p).append(System.lineSeparator());
        }
        msg.append(System.lineSeparator())
                .append("Run ./scripts/setup-env.sh --force on the host to generate fresh").append(System.lineSeparator())
                .append("secrets, then restart with `docker compose up -d --build`.").append(System.lineSeparator())
                .append("============================================================").append(System.lineSeparator());
        return msg.toString();
    }
}
