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
    private final ClientAddressResolver addresses;
    private static final int MAX_BUCKETS = 10_000;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ApiRateLimitFilter(
            @Value("${app.security.api-rate.per-minute:6000}") int perMinute,
            @Value("${app.security.api-rate.window-ms:60000}") long windowMs,
            Clock clock, ClientAddressResolver addresses) {
        this.limitPerWindow = perMinute;
        this.windowMs = windowMs;
        this.clock = clock;
        this.addresses = addresses;
    }

    public ApiRateLimitFilter(int limit, long window, Clock clock) {
        this(limit, window, clock, new ClientAddressResolver(""));
    }

    private synchronized Bucket bucket(String key, long now) {
        Bucket existing = buckets.get(key);
        if (existing != null) return existing;
        if (buckets.size() >= MAX_BUCKETS) {
            buckets.values().removeIf(b -> b.windowResetsAtMs <= now);
            if (buckets.size() >= MAX_BUCKETS) return null;
        }
        Bucket created = new Bucket(now);
        buckets.put(key, created);
        return created;
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
        Bucket b = bucket(key, clock.millis());
        if (b == null || !b.tryConsume(clock.millis())) {
            long retryAfterSec = b == null ? 60 : Math.max(1L, (b.windowResetsAtMs - clock.millis()) / 1000L);
            log.warn("rate limit tripped for key={} uri={} — {} req in {}ms window",
                    key, req.getRequestURI(), limitPerWindow, windowMs);
            res.setStatus(429);
            res.setHeader("Retry-After", Long.toString(retryAfterSec));
            res.setContentType("application/json");
            String msg = "Too many requests. Slow down and retry after " + retryAfterSec + " s.";
            res.getWriter().write("{\"error\":\"" + msg + "\",\"message\":\"" + msg + "\"}");
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
        return "ip:" + addresses.resolve(req);
    }

    private final class Bucket {
        private final AtomicLong count = new AtomicLong();
        private volatile long windowResetsAtMs;

        Bucket(long nowMs) {
            this.windowResetsAtMs = nowMs + windowMs;
        }

        synchronized boolean tryConsume(long nowMs) {
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
