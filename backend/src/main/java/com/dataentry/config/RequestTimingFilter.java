package com.dataentry.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Server-side per-request timing. Logs "GET /api/... 200 in 137 ms" once per request so we
 * can distinguish network latency from real backend work when a page feels slow. Runs first
 * (HIGHEST_PRECEDENCE) so it wraps every other filter in the chain — including the JWT auth
 * cache, so the measurement reflects total time on the server.
 *
 * <p>Enable/disable via {@code APP_REQUEST_TIMING=true|false} without a rebuild. Off by
 * default in tests, on by default in the container image so we can spot regressions.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTimingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestTimingFilter.class);

    private final boolean enabled;

    public RequestTimingFilter(@Value("${app.request-timing.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - startNanos) / 1_000_000L;
            // Skip the noisy actuator/health pings and hot polling endpoints — those aren't
            // useful signal and would flood the log.
            String uri = request.getRequestURI();
            if (uri == null || uri.startsWith("/actuator") || uri.endsWith("/notifications/unread-count")) {
                return;
            }
            log.info("{} {} {} in {} ms",
                    request.getMethod(), uri, response.getStatus(), ms);
        }
    }
}
