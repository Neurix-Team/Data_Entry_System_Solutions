package com.dataentry.security;

import com.dataentry.model.Role;
import com.dataentry.model.User;
import com.dataentry.repository.TeamRepository;
import com.dataentry.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class JwtAuthFilterTokenVersionTest {

    private static final String SECRET = "auth-filter-unit-test-secret-that-is-long-enough";
    private static final long EXP_MS = 60_000;

    private JwtService jwtService;
    private UserRepository userRepo;
    private TeamRepository teamRepo;
    private JwtAuthFilter filter;
    private User alice;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, EXP_MS);
        userRepo = Mockito.mock(UserRepository.class);
        teamRepo = Mockito.mock(TeamRepository.class);
        filter = new JwtAuthFilter(jwtService, userRepo, teamRepo);
        alice = User.builder()
                .id(1L).username("alice").passwordHash("x")
                .role(Role.USER).active(true).tokenVersion(5L)
                .build();
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(alice));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        filter.clearAuthCache();
    }

    @Test
    void current_tv_authenticates() throws Exception {
        String token = jwtService.generateToken("alice", "USER", 1L, null, 5L);
        MockHttpServletResponse res = run(token);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isInstanceOf(User.class);
    }

    @Test
    void higher_tv_in_token_than_db_still_authenticates() throws Exception {
        String token = jwtService.generateToken("alice", "USER", 1L, null, 6L);
        MockHttpServletResponse res = run(token);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void stale_tv_is_rejected() throws Exception {
        String token = jwtService.generateToken("alice", "USER", 1L, null, 4L);
        MockHttpServletResponse res = run(token);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void missing_tv_claim_is_treated_as_zero_for_backwards_compat() throws Exception {
        alice.setTokenVersion(0L);
        String legacyToken = legacyTokenWithoutTv("alice", 1L);
        MockHttpServletResponse res = run(legacyToken);
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("pre-migration token with no tv claim, user tv=0 → accepted")
                .isNotNull();
    }

    @Test
    void missing_tv_claim_is_rejected_once_the_user_has_bumped_past_zero() throws Exception {
        alice.setTokenVersion(1L);
        String legacyToken = legacyTokenWithoutTv("alice", 1L);
        MockHttpServletResponse res = run(legacyToken);
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("legacy token vs user tv=1 → rejected")
                .isNull();
    }

    private String legacyTokenWithoutTv(String username, Long userId) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("role", "USER")
                .claim("uid", userId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + EXP_MS))
                .signWith(key)
                .compact();
    }

    private MockHttpServletResponse run(String token) throws Exception {
        SecurityContextHolder.clearContext();
        filter.clearAuthCache();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/whatever");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        return res;
    }
}
