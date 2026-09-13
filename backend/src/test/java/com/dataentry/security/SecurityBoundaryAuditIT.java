package com.dataentry.security;

import com.dataentry.dto.TicketDtos;
import com.dataentry.model.*;
import com.dataentry.repository.*;
import com.dataentry.service.*;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in, safe local boundary tests; failing assertions indicate vulnerabilities. */
class SecurityBoundaryAuditIT {
    @TempDir Path temporary;
    @AfterEach void clear(){SecurityContextHolder.clearContext();TenantContext.clear();}

    @Test void proxyAppendedHeaderCannotResetLoginAttemptBudget() {
        var auth=mock(AuthService.class);
        when(auth.login(any())).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED,"fixture credentials rejected"));
        var controller=new com.dataentry.controller.AuthController(auth,new InMemoryLoginRateLimiter(2,300),true);
        int last=0;
        for(int i=0;i<3;i++) {
            var req=new MockHttpServletRequest();req.setRemoteAddr("172.18.0.4");
            // Default nginx appends the actual peer after a client-supplied header.
            req.addHeader("X-Forwarded-For","203.0.113."+i+", 198.51.100.12");
            try {controller.login(new com.dataentry.dto.AuthDtos.LoginRequest("fixture-user","wrong"),req);}
            catch(org.springframework.web.server.ResponseStatusException e){last=e.getStatusCode().value();}
        }
        assertThat(last).isEqualTo(429);
    }
    @Test void revokedAndExpiredApiTokensAreRejected() throws Exception {
        var repo=mock(ApiTokenRepository.class);
        var filter=new ApiTokenAuthFilter(repo);
        for(var record:List.of(
                ApiToken.builder().id(1L).name("revoked").revokedAt(Instant.now()).build(),
                ApiToken.builder().id(2L).name("expired").expiresAt(Instant.now().minusSeconds(10)).build())) {
            when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(record));
            var req=new MockHttpServletRequest("GET","/api/v1/export/tickets");
            req.addHeader("Authorization","Bearer nrx_fixture_only");
            filter.doFilter(req,new MockHttpServletResponse(),new MockFilterChain());
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Test void changingUntrustedForwardedForMustNotBypassRateLimit() throws Exception {
        ApiRateLimitFilter filter=new ApiRateLimitFilter(2,60000,Clock.systemUTC());
        int last=0;
        for(int i=0;i<3;i++) {
            MockHttpServletRequest req=new MockHttpServletRequest("GET","/api/auth/me");
            req.setRemoteAddr("198.51.100.12");
            req.addHeader("X-Forwarded-For","203.0.113."+i);
            MockHttpServletResponse res=new MockHttpServletResponse();
            filter.doFilter(req,res,new MockFilterChain());last=res.getStatus();
        }
        assertThat(last).isEqualTo(429);
    }
    @Test void missingImageReferenceCannotDeleteAnotherUsersExtraction() throws Exception {
        ExtractionStagingService staging=new ExtractionStagingService(temporary.resolve("staging").toString(),24);
        var victim=staging.create(2L);
        Path image=victim.directory().resolve("keep.png");Files.writeString(image,"fixture only");
        var documents=new TicketDocumentService(mock(TicketRepository.class),mock(TicketDocumentRepository.class),
                mock(UploadQuotaService.class),staging,temporary.resolve("attachments").toString(),
                temporary.resolve("incoming").toString(),1024);
        try {
            documents.attachExtractedImages(Ticket.builder().id(1L).build(),
                    List.of(new TicketDtos.ExtractedImageRef("sample",victim.extractionId(),"missing.png")),
                    User.builder().id(1L).role(Role.USER).build());
        } catch(org.springframework.web.server.ResponseStatusException expected) { }
        assertThat(image).as("another user's staged image must survive").exists();
    }
    @Test void extractionRootCannotBeDeletedThroughDotIdentifier() throws Exception {
        ExtractionStagingService staging=new ExtractionStagingService(temporary.resolve("staging").toString(),24);
        var victim=staging.create(2L);
        Path image=victim.directory().resolve("keep.png");Files.writeString(image,"fixture only");
        var documents=new TicketDocumentService(mock(TicketRepository.class),mock(TicketDocumentRepository.class),
                mock(UploadQuotaService.class),staging,temporary.resolve("attachments").toString(),
                temporary.resolve("incoming").toString(),1024);
        try {
            documents.attachExtractedImages(Ticket.builder().id(1L).build(),
                    List.of(new TicketDtos.ExtractedImageRef("sample",".","missing.png")),
                    User.builder().id(1L).role(Role.USER).build());
        } catch(org.springframework.web.server.ResponseStatusException expected) { }
        assertThat(image).as("all other staging directories must survive").exists();
    }
    @Test void stagingTraversalAndOtherOwnersAreBlocked() throws Exception {
        var staging=new ExtractionStagingService(temporary.resolve("staging").toString(),24);
        var owned=staging.create(1L);Files.writeString(owned.directory().resolve("sample.png"),"fixture");
        assertThatThrownBy(()->staging.resolveOwned(owned.extractionId(),"../../outside",1L))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->staging.resolveOwned(owned.extractionId(),"sample.png",2L))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void jwtUidMustMatchTheCurrentAccountIdentity() throws Exception {
        var jwt=new JwtService("audit-fixture-secret-key-not-a-real-production-secret",60000);
        var repo=mock(UserRepository.class);
        when(repo.findByUsername("reused-name")).thenReturn(Optional.of(User.builder().id(99L)
                .username("reused-name").role(Role.ADMIN).active(true).team(Team.builder().id(2L).build()).build()));
        var filter=new JwtAuthFilter(jwt,repo,mock(TeamRepository.class));
        String old=jwt.generateToken("reused-name","USER",1L,1L,0L);
        authenticate(filter,old);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    @Test void cachedJwtMustStillRespectExpiration() throws Exception {
        var jwt=new JwtService("audit-fixture-secret-key-not-a-real-production-secret",2000);
        var repo=mock(UserRepository.class);
        var user=User.builder().id(1L).username("audit").role(Role.USER).active(true)
                .team(Team.builder().id(1L).build()).build();
        when(repo.findByUsername("audit")).thenReturn(Optional.of(user));
        var filter=new JwtAuthFilter(jwt,repo,mock(TeamRepository.class));
        String token=jwt.generateToken("audit","USER",1L,1L,0L);
        Date expiration=jwt.parse(token).getExpiration();
        authenticate(filter,token);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(4))
                .until(()->new Date().after(expiration));
        assertThatThrownBy(()->jwt.parse(token)).isInstanceOf(io.jsonwebtoken.ExpiredJwtException.class);
        SecurityContextHolder.clearContext();
        authenticate(filter,token);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).as("expired cached JWT must be rejected").isNull();
    }

    @Test void legitimateImagePromotionPreservesOtherExtractions() throws Exception {
        var staging=new ExtractionStagingService(temporary.resolve("staging").toString(),24);
        var own=staging.create(1L);var other=staging.create(2L);
        Files.writeString(own.directory().resolve("sample.png"),"fixture");
        Path otherImage=other.directory().resolve("keep.png");Files.writeString(otherImage,"other");
        assertThat(staging.moveOut(own.extractionId(),"sample.png",1L,temporary.resolve("out/sample.png"))).isTrue();
        staging.discard(own.extractionId(),1L);
        assertThat(temporary.resolve("out/sample.png")).hasContent("fixture");
        assertThat(otherImage).exists();
    }
    @Test void trustedProxyUsesRightmostUntrustedHop() {
        var resolver=new ClientAddressResolver("172.18.0.4");
        var request=new MockHttpServletRequest();request.setRemoteAddr("172.18.0.4");
        request.addHeader("X-Forwarded-For","203.0.113.55, 198.51.100.12");
        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.12");
    }
    @Test void tenantGuardRejectsMissingContextAndUnownedRecords() {
        assertThatThrownBy(()->TenantGuard.assertOwnership(User.builder().build()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        TenantContext.set(1L,Role.ADMIN,1L,null);
        assertThatThrownBy(()->TenantGuard.assertOwnership(User.builder().role(Role.SUPER_ADMIN).build()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    void authenticate(JwtAuthFilter filter,String token) throws Exception {
        var req=new MockHttpServletRequest("GET","/api/auth/me");req.addHeader("Authorization","Bearer "+token);
        filter.doFilter(req,new MockHttpServletResponse(),new MockFilterChain());
    }
}
