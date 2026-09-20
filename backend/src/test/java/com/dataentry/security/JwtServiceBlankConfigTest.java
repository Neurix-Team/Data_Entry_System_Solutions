package com.dataentry.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * .env.example and docker-compose both hand the backend {@code JWT_EXPIRATION_MS=} (blank)
 * when the operator wants the default. That blank value used to stop the backend from
 * booting because Spring cannot convert "" to a long.
 */
class JwtServiceBlankConfigTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret-1234567890";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtService.class);

    @Test
    void blankLifetimesFallBackToDefaults() {
        runner.withPropertyValues(
                        "app.jwt.secret=" + SECRET,
                        "app.jwt.expiration-ms=",
                        "app.jwt.mfa-pending-expiration-ms=")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    JwtService jwt = ctx.getBean(JwtService.class);
                    assertThat(jwt.getExpirationMs()).isEqualTo(86_400_000L);
                    assertThat(jwt.getMfaPendingExpirationMs()).isEqualTo(300_000L);
                });
    }

    @Test
    void unsetLifetimesFallBackToDefaults() {
        runner.withPropertyValues("app.jwt.secret=" + SECRET)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(JwtService.class).getExpirationMs()).isEqualTo(86_400_000L);
                });
    }

    @Test
    void explicitLifetimesAreHonoured() {
        runner.withPropertyValues(
                        "app.jwt.secret=" + SECRET,
                        "app.jwt.expiration-ms=1800000",
                        "app.jwt.mfa-pending-expiration-ms=60000")
                .run(ctx -> {
                    JwtService jwt = ctx.getBean(JwtService.class);
                    assertThat(jwt.getExpirationMs()).isEqualTo(1_800_000L);
                    assertThat(jwt.getMfaPendingExpirationMs()).isEqualTo(60_000L);
                });
    }
}
