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
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Security regression tests included in the default Maven suite.
 * Uses generated fixtures only; optional PostgreSQL concurrency checks require audit.postgres=true. */
@SpringBootTest(properties = {
    "spring.config.import=", "app.seed.enabled=false", "app.translation.base-url=",
    "app.security.api-rate.per-minute=10000", "app.uploads.per-user-daily-bytes=1024", "management.server.port=0",
    "app.attachments.dir=${java.io.tmpdir}/neurix-security-audit/attachments",
    "app.uploads.incoming-dir=${java.io.tmpdir}/neurix-security-audit/incoming",
    "app.pdf.extractions-dir=${java.io.tmpdir}/neurix-security-audit/extractions"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityAuditIT {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired UserAvatarRepository avatars;
    @Autowired JwtService jwt;
    @Autowired JwtAuthFilter authFilter;
    @Autowired PasswordEncoder encoder;
    @Autowired com.dataentry.service.LoginRateLimiter loginLimiter;
    @Autowired com.dataentry.service.UploadQuotaService quota;
    @Autowired UploadUsageRepository uploadUsage;
    User admin, agent, other, root;
    Team team;
    static final String FIXTURE_PASSWORD = "FixtureOnly-7294-safe";

    @BeforeEach void fixtures() {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        String id = UUID.randomUUID().toString().substring(0,8);
        team = teams.save(Team.builder().slug("audit-a-"+id).name("Audit A").build());
        Team second = teams.save(Team.builder().slug("audit-b-"+id).name("Audit B").build());
        admin = save("admin-"+id, Role.ADMIN, team);
        agent = save("agent-"+id, Role.USER, team);
        other = save("other-"+id, Role.USER, second);
        root = save("root-"+id, Role.SUPER_ADMIN, null);
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
        return "Bearer "+jwt.generateToken(u.getUsername(),u.getRole().name(),u.getId(),
                u.getTeam()==null?null:u.getTeam().getId(),u.getTokenVersion());
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder csrfLogin() throws Exception {
        var response=mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var cookie=response.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return post("/api/auth/login").cookie(cookie).header("X-XSRF-TOKEN",cookie.getValue());
    }
    @Test void anonymousCannotReadProtectedResources() throws Exception {
        for(String path : new String[]{"/api/admin/users","/api/super/teams","/api/auth/me",
                "/api/user/tickets","/api/v1/export/tickets","/api/users/1/avatar"}) {
            int status=mvc.perform(get(path)).andReturn().getResponse().getStatus();
            assertThat(status).as(path).isIn(401,403);
        }
    }
    @Test void ordinaryUserCannotUseAdminOrSuperRoutes() throws Exception {
        for(String path : new String[]{"/api/admin/users","/api/super/teams"})
            mvc.perform(get(path).header("Authorization",bearer(agent))).andExpect(status().isForbidden());
    }
    @Test void ordinaryUserCannotImpersonateATeam() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization",bearer(agent))
                .header(JwtAuthFilter.IMPERSONATE_HEADER,other.getTeam().getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(agent.getId()));
    }
    @Test void adminCannotCreateSuperAdminRole() throws Exception {
        mvc.perform(post("/api/admin/users").header("Authorization",bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"audit-new-root\",\"password\":\"FixtureOnly-7294-safe\",\"role\":\"SUPER_ADMIN\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test void adminCannotModifyAnotherTeamsUser() throws Exception {
        mvc.perform(patch("/api/admin/users/"+other.getId()).header("Authorization",bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isNotFound());
    }
    @Test void adminCannotResetGlobalSuperAdminPassword() throws Exception {
        mvc.perform(patch("/api/admin/users/"+root.getId()).header("Authorization",bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"AuditReplacement-8274\"}"))
                .andExpect(status().isNotFound());
    }
    @Test void disabledTeamCannotLogIn() throws Exception {
        team.setActive(false); teams.save(team);
        mvc.perform(csrfLogin().contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\""+agent.getUsername()+"\",\"password\":\""+FIXTURE_PASSWORD+"\"}"))
                .andExpect(status().isForbidden());
    }
    @Test void teamMemberCannotReadAnotherTeamsAvatar() throws Exception {
        avatars.save(UserAvatar.builder().userId(other.getId()).contentType("image/png")
                .data(new byte[]{1,2,3}).updatedAt(Instant.now()).build());
        mvc.perform(get("/api/users/"+other.getId()+"/avatar").header("Authorization",bearer(agent)))
                .andExpect(status().isNotFound());
    }
    @Test void normalJwtCannotUseExternalExportApi() throws Exception {
        mvc.perform(get("/api/v1/export/tickets").header("Authorization",bearer(root)))
                .andExpect(status().isForbidden());
    }
    @Test void untrustedCorsOriginIsRejected() throws Exception {
        mvc.perform(options("/api/admin/users").header("Origin","https://untrusted.invalid")
                .header("Access-Control-Request-Method","PATCH"))
                .andExpect(status().isForbidden());
    }
    @Test void cookieHasTransportAndScriptProtection() throws Exception {
        mvc.perform(csrfLogin().contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\""+agent.getUsername()+"\",\"password\":\""+FIXTURE_PASSWORD+"\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("HttpOnly"),
                                org.hamcrest.Matchers.containsString("Secure"),org.hamcrest.Matchers.containsString("SameSite=Lax"))));
    }
    @Test void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization",bearer(agent)))
                .andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(header().string("X-Frame-Options","DENY"))
                .andExpect(header().exists("Content-Security-Policy"));
    }

    @Test void adminCannotDeleteOrDisableGlobalAdministrator() throws Exception {
        mvc.perform(delete("/api/admin/users/"+root.getId()).header("Authorization",bearer(admin)))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/admin/users/"+root.getId()).header("Authorization",bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isNotFound());
        assertThat(users.findById(root.getId()).orElseThrow().isActive()).isTrue();
    }
    @Test void adminCanUpdateOwnTeamUser() throws Exception {
        mvc.perform(patch("/api/admin/users/"+agent.getId()).header("Authorization",bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Updated Fixture\"}"))
                .andExpect(status().isOk());
    }
    @Test void disabledTeamCannotReuseExistingJwt() throws Exception {
        String token=bearer(agent);
        mvc.perform(get("/api/auth/me").header("Authorization",token)).andExpect(status().isOk());
        team.setActive(false);teams.save(team);
        mvc.perform(get("/api/auth/me").header("Authorization",token)).andExpect(status().isForbidden());
    }
    @Test void tokenVersionChangeRevokesExistingSessionImmediately() throws Exception {
        String token=bearer(agent);
        mvc.perform(get("/api/auth/me").header("Authorization",token)).andExpect(status().isOk());
        agent.setTokenVersion(agent.getTokenVersion()+1);users.save(agent);
        mvc.perform(get("/api/auth/me").header("Authorization",token)).andExpect(status().isForbidden());
    }
    @Test void sameTeamAvatarIsAvailableAndNotPubliclyCached() throws Exception {
        avatars.save(UserAvatar.builder().userId(agent.getId()).contentType("image/png")
                .data(new byte[]{1,2,3}).updatedAt(Instant.now()).build());
        mvc.perform(get("/api/users/"+agent.getId()+"/avatar").header("Authorization",bearer(admin)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void loginRequiresCsrfAndSupportsActualCookieTokenHandshake() throws Exception {
        String body="{\"username\":\""+agent.getUsername()+"\",\"password\":\""+FIXTURE_PASSWORD+"\"}";
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        var response=mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var cookie=response.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        mvc.perform(post("/api/auth/login").cookie(cookie).header("X-XSRF-TOKEN",cookie.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }
    @Test void cookieAuthenticatedMutationRequiresCsrf() throws Exception {
        var cookie=new jakarta.servlet.http.Cookie(JwtAuthFilter.AUTH_COOKIE,bearer(agent).substring(7));
        mvc.perform(patch("/api/auth/me").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Blocked\"}")).andExpect(status().isForbidden());
    }
    @Test void uploadBudgetIsPersistedAndCannotBeExceeded() {
        quota.chargeOrThrow(agent.getId(),700);
        assertThat(uploadUsage.findById(agent.getId()).orElseThrow().getChargedBytes()).isEqualTo(700);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->quota.chargeOrThrow(agent.getId(),400))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(uploadUsage.findById(agent.getId()).orElseThrow().getChargedBytes()).isEqualTo(700);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="audit.postgres",matches="true")
    void concurrentLoginAttemptsRespectDatabaseBudget() throws Exception {
        var executor=java.util.concurrent.Executors.newFixedThreadPool(12);
        var gate=new java.util.concurrent.CountDownLatch(1);
        String key="concurrency:"+UUID.randomUUID();
        try {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<24;i++) futures.add(executor.submit(()->{gate.await();return loginLimiter.tryAcquire(key);}));
            gate.countDown();
            int accepted=0;
            for(var result:futures) if(result.get(30,java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(10);
        } finally {executor.shutdownNow();}
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="audit.postgres",matches="true")
    void concurrentUploadsCannotOverspendPersistentBudget() throws Exception {
        var executor=java.util.concurrent.Executors.newFixedThreadPool(8);
        var gate=new java.util.concurrent.CountDownLatch(1);
        Long id=agent.getId();
        try {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<8;i++) futures.add(executor.submit(()->{
                gate.await();
                try {quota.chargeOrThrow(id,400);return true;}
                catch(org.springframework.web.server.ResponseStatusException denied) {
                    assertThat(denied.getStatusCode().value()).isEqualTo(413);return false;
                }
            }));
            gate.countDown();int accepted=0;
            for(var result:futures) if(result.get(30,java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(2);
            assertThat(uploadUsage.findById(id).orElseThrow().getChargedBytes()).isEqualTo(800);
        } finally {executor.shutdownNow();}
    }
}
