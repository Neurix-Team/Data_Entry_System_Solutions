package com.dataentry.controller;

import com.dataentry.model.*;
import com.dataentry.repository.*;
import com.dataentry.security.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-03 remediation tests: the TOTP second factor on the sign-in path — challenge response,
 * verification and durable lockout, first-run enrollment for mandatory roles, recovery
 * codes, and that an mfa_pending ticket can never be upgraded to a session.
 */
@SpringBootTest(properties = {
        "spring.config.import=", "app.seed.enabled=false", "app.translation.base-url=",
        "app.security.api-rate.per-minute=10000", "management.server.port=",
        "app.attachments.dir=${java.io.tmpdir}/neurix-mfa-test/attachments",
        "app.uploads.incoming-dir=${java.io.tmpdir}/neurix-mfa-test/incoming",
        "app.pdf.extractions-dir=${java.io.tmpdir}/neurix-mfa-test/extractions"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerMfaTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired MfaRecoveryCodeRepository recoveryCodes;
    @Autowired JwtService jwt;
    @Autowired TotpService totp;
    @Autowired SecretCipher cipher;
    @Autowired PasswordEncoder encoder;

    User agent;
    Team team;
    String secret;
    String suffix;
    static final String FIXTURE_PASSWORD = "FixtureOnly-7294-safe";

    @BeforeEach
    void fixtures() {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        suffix = UUID.randomUUID().toString().substring(0, 8);
        team = teams.save(Team.builder().slug("mfa-" + suffix).name("MFA Team").build());
        agent = save("agent-" + suffix, Role.USER, team);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // --- fixtures and helpers ------------------------------------------------------

    User save(String name, Role role, Team owner) {
        return users.save(User.builder().username(name)
                .passwordHash(encoder.encode(FIXTURE_PASSWORD))
                .role(role).team(owner).active(true).build());
    }

    /** Marks the account enrolled with a fresh device secret; returns the plaintext secret. */
    String enroll(User u) {
        String s = totp.newSecret();
        u.setMfaSecretEncrypted(cipher.encrypt(s));
        u.setMfaEnabled(true);
        u.setMfaEnabledAt(Instant.now());
        users.save(u);
        return s;
    }

    String pendingTicket(User u) {
        return jwt.generateMfaPendingToken(u.getUsername(), u.getRole().name(), u.getId(),
                u.getTeam() == null ? null : u.getTeam().getId(), u.getTokenVersion());
    }

    String bearer(User u) {
        return "Bearer " + jwt.generateToken(u.getUsername(), u.getRole().name(), u.getId(),
                u.getTeam() == null ? null : u.getTeam().getId(), u.getTokenVersion());
    }

    /** A well-formed code from far outside the clock window — reliably wrong. */
    String wrongCode() {
        return totp.codeAt(secret, totp.currentStep() + 50);
    }

    String loginChallenge(User u) throws Exception {
        String body = mvc.perform(csrfPost("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + u.getUsername()
                                + "\",\"password\":\"" + FIXTURE_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.mfaTicket");
    }

    MockHttpServletRequestBuilder csrfPost(String url) throws Exception {
        MvcResult csrf = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        jakarta.servlet.http.Cookie cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return post(url).cookie(cookie).header("X-XSRF-TOKEN", cookie.getValue());
    }

    MockHttpServletRequestBuilder verifyCall(String ticket, String code) throws Exception {
        return csrfPost("/api/auth/mfa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticket\":\"" + ticket + "\",\"code\":\"" + code + "\"}");
    }

    // --- sign-in challenge -----------------------------------------------------------

    @Test
    void loginReturnsChallengeInsteadOfASession() throws Exception {
        secret = enroll(agent);
        MvcResult result = mvc.perform(csrfPost("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + agent.getUsername()
                                + "\",\"password\":\"" + FIXTURE_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.mfaTicket").isNotEmpty())
                .andReturn();
        // No session cookie may exist while the second factor is still owed.
        assertThat(result.getResponse().getCookie(JwtAuthFilter.AUTH_COOKIE)).isNull();
    }

    @Test
    void verifyWithCorrectCodeIssuesTheSession() throws Exception {
        secret = enroll(agent);
        String ticket = loginChallenge(agent);
        MvcResult result = mvc.perform(verifyCall(ticket, totp.currentCode(secret)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(false))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value(agent.getUsername()))
                .andReturn();
        assertThat(result.getResponse().getCookie(JwtAuthFilter.AUTH_COOKIE)).isNotNull();
    }

    @Test
    void wrongCodesAreRejectedAndEventuallyLockTheAccountDurably() throws Exception {
        secret = enroll(agent);
        String ticket = pendingTicket(agent);
        for (int i = 0; i < 4; i++) {
            mvc.perform(verifyCall(ticket, wrongCode()))
                    .andExpect(status().isUnauthorized());
        }
        // The fifth failure trips the durable lock: even the CORRECT code is refused now.
        mvc.perform(verifyCall(ticket, wrongCode()))
                .andExpect(status().isTooManyRequests());
        mvc.perform(verifyCall(ticket, totp.currentCode(secret)))
                .andExpect(status().isTooManyRequests());
    }

    // --- pending-ticket containment (the security core) ------------------------------

    @Test
    void pendingTicketCannotReachProtectedApis() throws Exception {
        secret = enroll(agent);
        String ticket = pendingTicket(agent);
        mvc.perform(get("/api/user/dashboard/me").header("Authorization", "Bearer " + ticket))
                .andExpect(status().is4xxClientError());
        // ...but the routes the challenge itself needs stay reachable:
        mvc.perform(get("/api/auth/mfa/status").header("Authorization", "Bearer " + ticket))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(true));
    }

    @Test
    void pendingTicketCannotBeRefreshedIntoASession() throws Exception {
        secret = enroll(agent);
        String ticket = pendingTicket(agent);
        mvc.perform(csrfPost("/api/auth/refresh").header("Authorization", "Bearer " + ticket))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pendingTicketCannotEnrollOnAnAlreadyEnrolledAccount() throws Exception {
        secret = enroll(agent);
        String originalEncrypted = agent.getMfaSecretEncrypted();
        String ticket = pendingTicket(agent);
        mvc.perform(post("/api/auth/mfa/enroll")
                        .header("Authorization", "Bearer " + ticket)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + FIXTURE_PASSWORD + "\"}"))
                .andExpect(status().is4xxClientError());
        User reloaded = users.findByUsername(agent.getUsername()).orElseThrow();
        assertThat(reloaded.isMfaEnabled()).isTrue();
        assertThat(reloaded.getMfaSecretEncrypted()).isEqualTo(originalEncrypted);
    }

    @Test
    void mandatoryRoleEnrollsDuringFirstLogin() throws Exception {
        User admin = save("boss-" + suffix, Role.ADMIN, team);   // no device yet...
        String ticket = loginChallenge(admin);                   // ...and no session either

        String enrollBody = mvc.perform(post("/api/auth/mfa/enroll")
                        .header("Authorization", "Bearer " + ticket)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + FIXTURE_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String deviceSecret = com.jayway.jsonpath.JsonPath.read(enrollBody, "$.secret");

        mvc.perform(post("/api/auth/mfa/enroll/confirm")
                        .header("Authorization", "Bearer " + ticket)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + totp.currentCode(deviceSecret) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(10));

        // The ticket predates the enrollment bump, yet still finishes the challenge.
        mvc.perform(verifyCall(ticket, totp.codeAt(deviceSecret, totp.currentStep() + 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void recoveryCodeResolvesTheChallengeExactlyOnce() throws Exception {
        secret = enroll(agent);
        String code = "123-456";
        recoveryCodes.save(MfaRecoveryCode.builder()
                .userId(agent.getId()).codeHash(sha256Hex(code)).build());
        mvc.perform(verifyCall(pendingTicket(agent), code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(verifyCall(pendingTicket(agent), code))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disableRequiresAValidCodeAndIsBlockedForMandatoryRoles() throws Exception {
        // An opted-in USER can opt back out, but only with a working code.
        secret = enroll(agent);
        mvc.perform(post("/api/auth/mfa/disable")
                        .header("Authorization", bearer(agent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + wrongCode() + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/mfa/disable")
                        .header("Authorization", bearer(agent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + totp.currentCode(secret) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(false));

        // A privileged role may never self-disable, even with the right code.
        User admin = save("boss-" + suffix, Role.ADMIN, team);
        String adminSecret = enroll(admin);
        mvc.perform(post("/api/auth/mfa/disable")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + totp.currentCode(adminSecret) + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void adminCanResetAUsersSecondFactor() throws Exception {
        secret = enroll(agent);
        User admin = save("boss-" + suffix, Role.ADMIN, team);
        mvc.perform(post("/api/admin/users/" + agent.getId() + "/mfa/reset")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnabled").value(false));
        // The account signs in with the password alone again.
        mvc.perform(csrfPost("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + agent.getUsername()
                                + "\",\"password\":\"" + FIXTURE_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(false))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    private static String sha256Hex(String value) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
