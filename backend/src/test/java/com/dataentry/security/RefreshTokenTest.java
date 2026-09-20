package com.dataentry.security;

import com.dataentry.model.*;
import com.dataentry.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Stage-3 security tests: refresh flow, actuator lockdown (F-02), password-change
 * throttling (F-06). Runs in the default Maven suite on H2 with generated fixtures.
 */
@SpringBootTest(properties = {
    "spring.config.import=", "app.seed.enabled=false", "app.translation.base-url=",
    "app.security.api-rate.per-minute=10000", "management.server.port=",
    "app.attachments.dir=${java.io.tmpdir}/neurix-refresh-test/attachments",
    "app.uploads.incoming-dir=${java.io.tmpdir}/neurix-refresh-test/incoming",
    "app.pdf.extractions-dir=${java.io.tmpdir}/neurix-refresh-test/extractions"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefreshTokenTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder encoder;
    @Autowired com.dataentry.service.LoginRateLimiter limiter;

    User agent;
    Team team;
    static final String FIXTURE_PASSWORD = "FixtureOnly-7294-safe";
    static final String TEST_SECRET = "test-secret-key-that-is-long-enough-to-satisfy-hmac-sha256-min-32-chars";

    @BeforeEach void fixtures() {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        String id = UUID.randomUUID().toString().substring(0, 8);
        team = teams.save(Team.builder().slug("refresh-" + id).name("Refresh Team").build());
        agent = save("agent-" + id, Role.USER, team);
    }
    @AfterEach void clear() {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
    User save(String name, Role role, Team owner) {
        return users.save(User.builder().username(name).passwordHash(encoder.encode(FIXTURE_PASSWORD))
                .role(role).team(owner).active(true).build());
    }
    String bearer(User u) {
        return "Bearer " + jwt.generateToken(u.getUsername(), u.getRole().name(), u.getId(),
                u.getTeam() == null ? null : u.getTeam().getId(), u.getTokenVersion());
    }
    MockHttpServletRequestBuilder csrfPost(String url) throws Exception {
        var response = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var cookie = response.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return post(url).cookie(cookie).header("X-XSRF-TOKEN", cookie.getValue());
    }
    String tokenFrom(MvcResult result) {
        var cookie = result.getResponse().getCookie(JwtAuthFilter.AUTH_COOKIE);
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    /** Hand-crafts a token with a custom expiry, signed with the shared test key. */
    // --- Refresh flow (F-04) ------------------------------------------------------

    @Test void refreshWithValidBearerIssuesFreshTokenAndCookie() throws Exception {
        String old = jwt.generateToken(agent.getUsername(), agent.getRole().name(),
                agent.getId(), team.getId(), agent.getTokenVersion());
        MvcResult result = mvc.perform(csrfPost("/api/auth/refresh")
                        .header("Authorization", "Bearer " + old))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(cookie().exists(JwtAuthFilter.AUTH_COOKIE))
                .andReturn();
        var cookie = result.getResponse().getCookie(JwtAuthFilter.AUTH_COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getMaxAge()).isGreaterThan(0);
    }

    @Test void refreshWithGarbageTokenIsUnauthorized() throws Exception {
        mvc.perform(csrfPost("/api/auth/refresh").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test void refreshWithNoSessionIsUnauthorized() throws Exception {
        mvc.perform(csrfPost("/api/auth/refresh")).andExpect(status().isUnauthorized());
    }

    @Test void expiredTokenWithinGraceRefreshes() throws Exception {
        String expired = tokenExpiringIn(-1_800_000); // expired 30 min ago
        mvc.perform(csrfPost("/api/auth/refresh").header("Authorization", "Bearer " + expired))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test void tokenExpiredBeyondGraceIsRejected() throws Exception {
        String ancient = tokenExpiringIn(-8L * 24 * 3_600_000); // expired 8 days ago
        mvc.perform(csrfPost("/api/auth/refresh").header("Authorization", "Bearer " + ancient))
                .andExpect(status().isUnauthorized());
    }

    @Test void revokedTokenVersionBlocksRefreshEvenInGrace() throws Exception {
        String old = jwt.generateToken(agent.getUsername(), agent.getRole().name(),
                agent.getId(), team.getId(), agent.getTokenVersion());
        agent.setTokenVersion(agent.getTokenVersion() + 1); // logout-everywhere bump
        users.save(agent);
        mvc.perform(csrfPost("/api/auth/refresh").header("Authorization", "Bearer " + old))
                .andExpect(status().isUnauthorized());
    }

    @Test void disabledAccountCannotRefresh() throws Exception {
        String old = jwt.generateToken(agent.getUsername(), agent.getRole().name(),
                agent.getId(), team.getId(), agent.getTokenVersion());
        agent.setActive(false);
        users.save(agent);
        mvc.perform(csrfPost("/api/auth/refresh").header("Authorization", "Bearer " + old))
                .andExpect(status().isUnauthorized());
    }

    @Test void refreshedTokenWorksForAuthenticatedCalls() throws Exception {
        String old = jwt.generateToken(agent.getUsername(), agent.getRole().name(),
                agent.getId(), team.getId(), agent.getTokenVersion());
        String fresh = tokenFrom(mvc.perform(csrfPost("/api/auth/refresh")
                        .header("Authorization", "Bearer " + old))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + fresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(agent.getUsername()));
    }

    @Test void csrfIsStillEnforcedOnRefreshForCookieOnlyClients() throws Exception {
        String old = jwt.generateToken(agent.getUsername(), agent.getRole().name(),
                agent.getId(), team.getId(), agent.getTokenVersion());
        mvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie(JwtAuthFilter.AUTH_COOKIE, old)))
                .andExpect(status().isForbidden());
    }

    // --- F-02: actuator lockdown --------------------------------------------------

    @Test void sensitiveActuatorEndpointsAreDenied() throws Exception {
        for (String path : new String[]{"/actuator/env", "/actuator/heapdump",
                "/actuator/loggers", "/actuator/threaddump", "/actuator/configprops",
                "/actuator/mappings", "/actuator/scheduledtasks"}) {
            int status = mvc.perform(get(path)).andReturn().getResponse().getStatus();
            assertThat(status).as(path + " must not be public").isNotEqualTo(200);
        }
    }

    // --- F-06: password-change throttling -----------------------------------------

    @Test void passwordChangeIsRateLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(csrfPost("/api/auth/me/password")
                            .header("Authorization", bearer(agent))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"wrong-" + i + "\",\"newPassword\":\"Whatever-1234-x\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(csrfPost("/api/auth/me/password")
                        .header("Authorization", bearer(agent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong-99\",\"newPassword\":\"Whatever-1234-x\"}"))
                .andExpect(status().isTooManyRequests());
    }

    String tokenExpiringIn(long millisFromNow) {
        javax.crypto.SecretKey key =
                io.jsonwebtoken.security.Keys.hmacShaKeyFor(TEST_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.util.Date now = new java.util.Date();
        return io.jsonwebtoken.Jwts.builder()
                .subject(agent.getUsername())
                .claims(java.util.Map.of("role", agent.getRole().name(),
                        "uid", agent.getId(), "tid", team.getId(), "tv", agent.getTokenVersion()))
                .issuedAt(new java.util.Date(now.getTime() - 3_600_000))
                .expiration(new java.util.Date(now.getTime() + millisFromNow))
                .signWith(key).compact();
    }
}
