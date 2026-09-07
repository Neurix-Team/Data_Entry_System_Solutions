package com.dataentry.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRateLimitFilterTest {

    private static final int LIMIT = 5;
    private static final long WINDOW_MS = 1_000;

    private MutableClock clock;
    private ApiRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(0);
        filter = new ApiRateLimitFilter(LIMIT, WINDOW_MS, clock);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void first_N_requests_pass_and_the_next_returns_429() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            MockHttpServletResponse res = runOnce("1.2.3.4");
            assertThat(res.getStatus()).as("request %d", i + 1).isEqualTo(200);
        }
        MockHttpServletResponse blocked = runOnce("1.2.3.4");
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotNull();
        assertThat(Integer.parseInt(blocked.getHeader("Retry-After"))).isBetween(1, (int) WINDOW_MS / 1000);
    }

    @Test
    void limit_resets_when_window_rolls() throws Exception {
        for (int i = 0; i < LIMIT; i++) runOnce("1.2.3.4");
        assertThat(runOnce("1.2.3.4").getStatus()).isEqualTo(429);

        clock.advance(WINDOW_MS + 1);

        assertThat(runOnce("1.2.3.4").getStatus()).isEqualTo(200);
    }

    @Test
    void separate_ips_have_independent_buckets() throws Exception {
        for (int i = 0; i < LIMIT; i++) runOnce("1.1.1.1");
        assertThat(runOnce("1.1.1.1").getStatus()).isEqualTo(429);
        assertThat(runOnce("2.2.2.2").getStatus()).isEqualTo(200);
    }

    @Test
    void authenticated_users_get_their_own_bucket_separate_from_the_ip() throws Exception {
        signIn("alice");
        for (int i = 0; i < LIMIT; i++) runOnce("1.1.1.1");
        assertThat(runOnce("1.1.1.1").getStatus()).isEqualTo(429);

        SecurityContextHolder.clearContext();
        assertThat(runOnce("1.1.1.1").getStatus()).isEqualTo(200);
    }

    @Test
    void actuator_paths_skip_the_filter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        req.setRemoteAddr("1.2.3.4");
        for (int i = 0; i < LIMIT * 3; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain());
            assertThat(res.getStatus()).as("actuator hit %d", i + 1).isEqualTo(200);
        }
    }

    @Test
    void forwarded_for_takes_precedence_over_remote_addr() throws Exception {
        for (int i = 0; i < LIMIT; i++) runOnceWithXff("10.0.0.1", "203.0.113.5");
        assertThat(runOnceWithXff("10.0.0.1", "203.0.113.5").getStatus()).isEqualTo(429);
        assertThat(runOnceWithXff("10.0.0.1", "203.0.113.6").getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse runOnce(String ip) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/whatever");
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    private MockHttpServletResponse runOnceWithXff(String remote, String xff) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/whatever");
        req.setRemoteAddr(remote);
        req.addHeader("X-Forwarded-For", xff);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    private void signIn(String username) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                username, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static final class MutableClock extends Clock {
        private long millis;
        MutableClock(long start) { this.millis = start; }
        void advance(long delta) { this.millis += delta; }
        @Override public long millis() { return millis; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
