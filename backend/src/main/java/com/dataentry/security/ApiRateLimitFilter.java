package com.dataentry.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiRateLimitFilter.class);

    private final int limitPerWindow;
    private final long windowMs;
    private final Clock clock;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public ApiRateLimitFilter(
            @Value("${app.security.api-rate.per-minute:600}") int perMinute,
            @Value("${app.security.api-rate.window-ms:60000}") long windowMs,
            Clock clock) {
        this.limitPerWindow = perMinute;
        this.windowMs = windowMs;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String uri = req.getRequestURI();
        return uri.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String key = principalOrIp(req);
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(clock.millis()));
        if (!b.tryConsume(clock.millis())) {
            long retryAfterSec = Math.max(1L, (b.windowResetsAtMs - clock.millis()) / 1000L);
            log.warn("rate limit tripped for key={} uri={} — {} req in {}ms window",
                    key, req.getRequestURI(), limitPerWindow, windowMs);
            res.setStatus(429);
            res.setHeader("Retry-After", Long.toString(retryAfterSec));
            res.setContentType("application/json");
            res.getWriter().write(
                    "{\"error\":\"Too many requests. Slow down and retry after "
                    + retryAfterSec + " s.\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    private String principalOrIp(HttpServletRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null
                && !"anonymousUser".equals(auth.getName())) {
            return "u:" + auth.getName();
        }
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return "ip:" + (comma > 0 ? xff.substring(0, comma).trim() : xff.trim());
        }
        return "ip:" + req.getRemoteAddr();
    }

    private final class Bucket {
        private final AtomicLong count = new AtomicLong();
        private volatile long windowResetsAtMs;

        Bucket(long nowMs) {
            this.windowResetsAtMs = nowMs + windowMs;
        }

        boolean tryConsume(long nowMs) {
            if (nowMs >= windowResetsAtMs) {
                synchronized (this) {
                    if (nowMs >= windowResetsAtMs) {
                        count.set(0);
                        windowResetsAtMs = nowMs + windowMs;
                    }
                }
            }
            return count.incrementAndGet() <= limitPerWindow;
        }
    }
}
