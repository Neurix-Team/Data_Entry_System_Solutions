package com.dataentry.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTokenVersionTest {

    private static final String SECRET = "unit-test-secret-that-is-plenty-long-enough";
    private static final long EXP_MS = 60_000;

    @Test
    void generated_token_carries_the_supplied_tv() {
        JwtService svc = new JwtService(SECRET, EXP_MS);
        String token = svc.generateToken("alice", "USER", 1L, 7L, 42L);
        Claims c = svc.parse(token);
        assertThat(c.get("tv", Number.class).longValue()).isEqualTo(42L);
        assertThat(c.getSubject()).isEqualTo("alice");
        assertThat(c.get("role", String.class)).isEqualTo("USER");
        assertThat(c.get("uid", Number.class).longValue()).isEqualTo(1L);
        assertThat(c.get("tid", Number.class).longValue()).isEqualTo(7L);
    }

    @Test
    void tv_starts_at_zero_for_new_users() {
        JwtService svc = new JwtService(SECRET, EXP_MS);
        String token = svc.generateToken("bob", "ADMIN", 2L, null, 0L);
        Claims c = svc.parse(token);
        assertThat(c.get("tv", Number.class).longValue()).isZero();
        assertThat(c.get("tid")).isNull();
    }

    @Test
    void two_tokens_with_different_tvs_verify_but_are_distinguishable() {
        JwtService svc = new JwtService(SECRET, EXP_MS);
        String old = svc.generateToken("alice", "USER", 1L, 7L, 3L);
        String fresh = svc.generateToken("alice", "USER", 1L, 7L, 4L);

        assertThat(svc.parse(old).get("tv", Number.class).longValue()).isEqualTo(3L);
        assertThat(svc.parse(fresh).get("tv", Number.class).longValue()).isEqualTo(4L);
    }
}
