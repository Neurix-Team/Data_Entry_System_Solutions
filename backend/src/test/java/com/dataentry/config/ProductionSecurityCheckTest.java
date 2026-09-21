package com.dataentry.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecurityCheckTest {

    private static final String GOOD_PW = "not-the-default-and-not-guessable";
    private static final String GOOD_JWT = "an-actually-strong-secret-abcdef12";
    private static final String GOOD_ORIGINS = "https://dataentry.example.com";
    private static final String GOOD_VAPID = "a-rotated-vapid-private-key";

    @Test
    void passes_when_all_secrets_rotated() {
        ProductionSecurityCheck c = new ProductionSecurityCheck(GOOD_PW, GOOD_PW, GOOD_JWT, GOOD_ORIGINS, GOOD_VAPID);
        assertThat(c.collectProblems()).isEmpty();
        c.check();
    }

    @Test
    void fails_when_admin_password_is_default() {
        ProductionSecurityCheck c = new ProductionSecurityCheck("admin123", GOOD_PW, GOOD_JWT, GOOD_ORIGINS, GOOD_VAPID);
        assertThatThrownBy(c::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_SEED_ADMIN_PASSWORD");
    }

    @Test
    void fails_when_superadmin_password_is_default() {
        ProductionSecurityCheck c = new ProductionSecurityCheck(GOOD_PW, "superadmin123", GOOD_JWT, GOOD_ORIGINS, GOOD_VAPID);
        assertThatThrownBy(c::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_SEED_SUPERADMIN_PASSWORD");
    }

    @Test
    void fails_when_jwt_secret_is_the_application_yml_placeholder() {
        String placeholder = "change-me-in-production-a-very-long-random-secret-key-min-32-chars";
        ProductionSecurityCheck c = new ProductionSecurityCheck(GOOD_PW, GOOD_PW, placeholder, GOOD_ORIGINS, GOOD_VAPID);
        assertThatThrownBy(c::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("placeholder");
    }

    @Test
    void fails_when_jwt_secret_is_the_historical_committed_placeholder() {
        String historical = "local-dev-secret-please-rotate-me-with-a-real-random-string-32chars-min";
        ProductionSecurityCheck c = new ProductionSecurityCheck(GOOD_PW, GOOD_PW, historical, GOOD_ORIGINS, GOOD_VAPID);
        assertThatThrownBy(c::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("git history");
    }

    @Test
    void fails_when_jwt_secret_is_too_short() {
        ProductionSecurityCheck c = new ProductionSecurityCheck(GOOD_PW, GOOD_PW, "short", GOOD_ORIGINS, GOOD_VAPID);
        assertThatThrownBy(c::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("32");
    }

    @Test
    void fails_when_jwt_secret_is_blank() {
        ProductionSecurityCheck c = new ProductionSecurityCheck(GOOD_PW, GOOD_PW, "", GOOD_ORIGINS, GOOD_VAPID);
        assertThatThrownBy(c::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("empty");
    }

    @Test
    void fails_when_cors_origins_blank() {
        ProductionSecurityCheck c = new ProductionSecurityCheck(GOOD_PW, GOOD_PW, GOOD_JWT, "", GOOD_VAPID);
        assertThatThrownBy(c::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_CORS_ALLOWED_ORIGINS");
    }

    @Test
    void reports_every_problem_in_a_single_message() {
        ProductionSecurityCheck c = new ProductionSecurityCheck("admin123", "superadmin123", "short", "", GOOD_VAPID);
        assertThatThrownBy(c::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContainingAll(
                        "APP_SEED_ADMIN_PASSWORD",
                        "APP_SEED_SUPERADMIN_PASSWORD",
                        "JWT_SECRET",
                        "APP_CORS_ALLOWED_ORIGINS");
    }

    @Test
    void warns_but_still_boots_when_the_bundled_push_keypair_is_in_use() {
        ProductionSecurityCheck c = new ProductionSecurityCheck(GOOD_PW, GOOD_PW, GOOD_JWT,
                GOOD_ORIGINS, ProductionSecurityCheck.BUNDLED_VAPID_PRIVATE_KEY);
        assertThat(c.collectWarnings()).singleElement()
                .asString().contains("WEB_PUSH_VAPID_PRIVATE_KEY");
        // A push-signing key cannot forge a session, so it must never take a deployment down.
        assertThat(c.collectProblems()).isEmpty();
        c.check();
    }

    @Test
    void says_nothing_about_push_keys_once_they_are_rotated() {
        ProductionSecurityCheck c = new ProductionSecurityCheck(GOOD_PW, GOOD_PW, GOOD_JWT,
                GOOD_ORIGINS, GOOD_VAPID);
        assertThat(c.collectWarnings()).isEmpty();
    }
}
