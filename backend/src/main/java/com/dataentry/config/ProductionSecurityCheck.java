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

    /**
     * The Web Push keypair shipped in application.yml so local development works out of the
     * box. Anyone holding this private key can sign push messages that browsers will accept
     * as ours, so it must never reach a deployment.
     */
    static final String BUNDLED_VAPID_PRIVATE_KEY = "2mOGx3EDVfd1TDzayhj3JXYecapNezPCj6Hjan5H4AA";

    static final int MIN_JWT_SECRET_LENGTH = 32;

    private final String adminPassword;
    private final String superAdminPassword;
    private final String jwtSecret;
    private final String corsOrigins;
    private final String vapidPrivateKey;

    public ProductionSecurityCheck(
            @Value("${app.seed.admin-password:}") String adminPassword,
            @Value("${app.seed.superadmin-password:}") String superAdminPassword,
            @Value("${app.jwt.secret:}") String jwtSecret,
            @Value("${app.cors.allowed-origins:}") String corsOrigins,
            @Value("${app.push.vapid-private-key:}") String vapidPrivateKey) {
        this.adminPassword = adminPassword;
        this.superAdminPassword = superAdminPassword;
        this.jwtSecret = jwtSecret;
        this.corsOrigins = corsOrigins;
        this.vapidPrivateKey = vapidPrivateKey;
    }

    @PostConstruct
    void check() {
        for (String warning : collectWarnings()) {
            log.warn("Production security warning: {}", warning);
        }
        List<String> problems = collectProblems();
        if (problems.isEmpty()) {
            log.info("Production security check passed — no built-in defaults detected.");
            return;
        }
        throw new IllegalStateException(formatMessage(problems));
    }

    /**
     * Weaknesses worth saying out loud on every boot, but not worth refusing to boot over.
     * The distinction is what the secret can do: anything that forges a session or hands out
     * an account belongs in {@link #collectProblems()} and stops the application; this list is
     * for the rest.
     */
    List<String> collectWarnings() {
        List<String> warnings = new ArrayList<>();
        if (BUNDLED_VAPID_PRIVATE_KEY.equals(vapidPrivateKey)) {
            warnings.add("WEB_PUSH_VAPID_PRIVATE_KEY is still the keypair bundled in "
                    + "application.yml for local development. It is published in this "
                    + "repository, so anyone could sign browser push messages as this "
                    + "deployment. Generate a pair — the command is in .env.example.");
        }
        return warnings;
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
