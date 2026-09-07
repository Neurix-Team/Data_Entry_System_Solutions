package com.dataentry.security;

import com.dataentry.model.ApiToken;
import com.dataentry.repository.ApiTokenRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ApiTokenAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenAuthFilter.class);

    public static final String TOKEN_PREFIX = "nrx_";

    private static final int RATE_LIMIT_PER_MINUTE = 120;

    private final ApiTokenRepository repository;
    private final Map<Long, WindowCounter> counters = new ConcurrentHashMap<>();

    public ApiTokenAuthFilter(ApiTokenRepository repository) {
        this.repository = repository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String bearer = extractBearer(request);
        if (bearer != null && bearer.startsWith(TOKEN_PREFIX)) {
            String hash = sha256Hex(bearer);
            Optional<ApiToken> tokenOpt = repository.findByTokenHash(hash);
            Instant now = Instant.now();
            if (tokenOpt.isPresent() && tokenOpt.get().isUsable(now)) {
                ApiToken token = tokenOpt.get();
                if (!allow(token.getId(), now)) {
                    response.setStatus(429);
                    response.setHeader("Retry-After", "60");
                    response.getWriter().write("{\"error\":\"rate_limited\"}");
                    return;
                }
                try {
                    token.setLastUsedAt(now);
                    repository.save(token);
                } catch (Exception ignored) { }

                List<SimpleGrantedAuthority> auth = List.of(new SimpleGrantedAuthority("ROLE_API"));
                var principal = new ApiPrincipal(token.getId(), token.getName());
                var authToken = new UsernamePasswordAuthenticationToken(principal, null, auth);
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            } else if (log.isDebugEnabled()) {
                log.debug("Rejected API token for {} {} (usable={})",
                        request.getMethod(), request.getRequestURI(), tokenOpt.isPresent());
            }
        }
        chain.doFilter(request, response);
    }

    private String extractBearer(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        String value = auth.substring(7).trim();
        return value.isEmpty() ? null : value;
    }

    public static String sha256Hex(String plaintext) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record ApiPrincipal(Long tokenId, String tokenName) {}

    private boolean allow(Long tokenId, Instant now) {
        long windowStart = now.getEpochSecond() / 60L;
        WindowCounter c = counters.computeIfAbsent(tokenId, k -> new WindowCounter(windowStart));
        synchronized (c) {
            if (c.window != windowStart) {
                c.window = windowStart;
                c.count.set(0);
            }
            return c.count.incrementAndGet() <= RATE_LIMIT_PER_MINUTE;
        }
    }

    private static final class WindowCounter {
        long window;
        final AtomicLong count = new AtomicLong();
        WindowCounter(long window) { this.window = window; }
    }
}
