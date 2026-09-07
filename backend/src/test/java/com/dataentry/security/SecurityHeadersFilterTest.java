package com.dataentry.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersFilterTest {

    private SecurityHeadersFilter filter;
    private MockHttpServletRequest req;
    private MockHttpServletResponse res;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new SecurityHeadersFilter();
        req = new MockHttpServletRequest("GET", "/api/auth/me");
        res = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    @Test
    void stamps_the_full_hardening_set() throws Exception {
        filter.doFilter(req, res, chain);

        assertThat(res.getHeader("Strict-Transport-Security")).contains("max-age=31536000").contains("includeSubDomains");
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(res.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(res.getHeader("Content-Security-Policy"))
                .contains("default-src 'none'")
                .contains("frame-ancestors 'none'");
        assertThat(res.getHeader("Permissions-Policy")).contains("camera=()").contains("microphone=()");
        assertThat(res.getHeader("X-Permitted-Cross-Domain-Policies")).isEqualTo("none");
    }

    @Test
    void does_not_duplicate_hsts_when_a_downstream_component_set_it_first() throws Exception {
        res.setHeader("Strict-Transport-Security", "max-age=63072000; preload");

        filter.doFilter(req, res, chain);

        assertThat(res.getHeader("Strict-Transport-Security")).isEqualTo("max-age=63072000; preload");
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }
}
