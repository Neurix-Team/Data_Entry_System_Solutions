package com.dataentry.security;

import com.dataentry.model.Role;
import com.dataentry.model.Team;
import com.dataentry.model.User;
import com.dataentry.repository.TeamRepository;
import com.dataentry.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    /** Header used by a SUPER_ADMIN to "enter" a specific team from the super-admin UI. */
    public static final String IMPERSONATE_HEADER = "X-Impersonate-Team-Id";

    /** Name of the httpOnly cookie used for browser sessions. Kept in sync with AuthController. */
    public static final String AUTH_COOKIE = "dems_auth";

    /**
     * Short-lived cache of authenticated principals keyed by (JWT + impersonation header).
     * The previous implementation ran {@code userRepository.findByUsername} on every request
     * — a single DB round-trip that accounted for ~100–200 ms of the total request latency on
     * warm endpoints. Cached entries expire after {@link #AUTH_CACHE_TTL_NS} so
     * deactivations, role changes, and team edits take effect within a few seconds.
     * Cache size is bounded so a stream of unique/expired tokens can't grow the map without
     * limit (a very cheap sweep runs when {@link #AUTH_CACHE_MAX_ENTRIES} is exceeded).
     */
    private static final long AUTH_CACHE_TTL_NS = TimeUnit.SECONDS.toNanos(30);
    private static final int AUTH_CACHE_MAX_ENTRIES = 5_000;
    private final ConcurrentHashMap<String, AuthCacheEntry> authCache = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepNs = new AtomicLong(0);

    private record AuthCacheEntry(long expiresAtNs,
                                  User user,
                                  Long teamId,
                                  Role effectiveRole,
                                  Long impersonatedBy,
                                  List<SimpleGrantedAuthority> authorities) {}

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;

    public JwtAuthFilter(JwtService jwtService,
                         UserRepository userRepository,
                         TeamRepository teamRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
    }

    /**
     * Force-invalidate all cached principals (invoked from admin flows that mutate a user's
     * team/role/active state — see {@link #evictUser(Long)} for a targeted variant).
     */
    public void clearAuthCache() {
        authCache.clear();
    }

    /**
     * Drop every cache entry for the given user id. Called when an admin/super admin
     * changes a user's role, team, or active flag so the next request re-reads the DB
     * instead of honoring stale cached authorities.
     */
    public void evictUser(Long userId) {
        if (userId == null) return;
        authCache.values().removeIf(e -> userId.equals(e.user().getId()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);
        try {
            if (token != null) {
                try {
                    // Cache key must include the impersonation header — the same JWT presented
                    // with different X-Impersonate-Team-Id headers resolves to different
                    // effective scopes, so we can't collapse them into one entry.
                    String impersonateHeader = request.getHeader(IMPERSONATE_HEADER);
                    String cacheKey = impersonateHeader == null
                            ? token
                            : token + "" + impersonateHeader;
                    AuthCacheEntry cached = authCache.get(cacheKey);
                    long now = System.nanoTime();
                    if (cached != null && cached.expiresAtNs() > now) {
                        applyAuth(request, cached);
                    } else {
                        Claims claims = jwtService.parse(token);
                        String username = claims.getSubject();
                        if (username != null
                                && SecurityContextHolder.getContext().getAuthentication() == null) {
                            Optional<User> userOpt = userRepository.findByUsername(username);
                            if (userOpt.isPresent() && userOpt.get().isActive()) {
                                AuthCacheEntry entry = buildEntry(request, userOpt.get());
                                storeInCache(cacheKey, entry);
                                applyAuth(request, entry);
                            }
                        }
                    }
                } catch (JwtException e) {
                    // Invalid/expired token — leave context unauthenticated, but log so we can
                    // diagnose auth issues from server logs instead of guessing.
                    log.debug("Rejected JWT for {} {}: {}",
                            request.getMethod(), request.getRequestURI(), e.getMessage());
                }
            }
            chain.doFilter(request, response);
        } finally {
            // Container threads are pooled — a leaked tenant would let the next request run
            // with someone else's team scope. Always clear.
            TenantContext.clear();
        }
    }

    private void storeInCache(String key, AuthCacheEntry entry) {
        authCache.put(key, entry);
        if (authCache.size() > AUTH_CACHE_MAX_ENTRIES) {
            // Cheap opportunistic sweep — remove expired entries and, if still over, drop
            // arbitrary ones. Only one thread runs the sweep at a time; the throttling
            // prevents a hot-path burst from thrashing the map.
            long now = System.nanoTime();
            long last = lastSweepNs.get();
            if (now - last > TimeUnit.SECONDS.toNanos(5)
                    && lastSweepNs.compareAndSet(last, now)) {
                authCache.values().removeIf(e -> e.expiresAtNs() <= now);
                if (authCache.size() > AUTH_CACHE_MAX_ENTRIES) {
                    int toDrop = authCache.size() - AUTH_CACHE_MAX_ENTRIES;
                    var it = authCache.entrySet().iterator();
                    while (toDrop-- > 0 && it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }
            }
        }
    }

    private AuthCacheEntry buildEntry(HttpServletRequest request, User user) {
        Role role = user.getRole();
        Long teamId = user.getTeam() != null ? user.getTeam().getId() : null;
        Long impersonatedBy = null;

        // A SUPER_ADMIN may opt into a specific team's scope by sending X-Impersonate-Team-Id.
        // For that request only, they behave like an ADMIN of the target team — the tenant
        // filter is enabled and admin-only endpoints become reachable. Audit logs still
        // attribute the action back to the super admin id.
        if (role == Role.SUPER_ADMIN) {
            Long headerTeam = parseHeaderTeamId(request);
            if (headerTeam != null && teamExists(headerTeam)) {
                teamId = headerTeam;
                impersonatedBy = user.getId();
                role = Role.ADMIN; // functional role for the tenant filter + URL access
            }
        }

        // Grant authorities: the actual DB role plus (for super admins) ROLE_ADMIN so the
        // impersonation path can reach /api/admin/** without needing separate auth logic.
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(3);
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        if (user.getRole() == Role.SUPER_ADMIN) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        } else if (user.getRole() == Role.ADMIN) {
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        }

        return new AuthCacheEntry(
                System.nanoTime() + AUTH_CACHE_TTL_NS,
                user,
                teamId,
                role,
                impersonatedBy,
                List.copyOf(authorities)
        );
    }

    private void applyAuth(HttpServletRequest request, AuthCacheEntry entry) {
        if (SecurityContextHolder.getContext().getAuthentication() != null) return;
        TenantContext.set(entry.teamId(), entry.effectiveRole(),
                entry.user().getId(), entry.impersonatedBy());
        var authToken = new UsernamePasswordAuthenticationToken(
                entry.user(), null, entry.authorities());
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }

    private Long parseHeaderTeamId(HttpServletRequest request) {
        String raw = request.getHeader(IMPERSONATE_HEADER);
        if (raw == null || raw.isBlank()) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.debug("Ignoring malformed {} header: {}", IMPERSONATE_HEADER, raw);
            return null;
        }
    }

    private boolean teamExists(Long id) {
        // Guard against a super admin sending a random or deleted team id — otherwise
        // the aspect would enable the filter for a team that isn't real, hiding everything
        // and producing confusing empty screens.
        Optional<Team> t = teamRepository.findById(id);
        return t.isPresent() && t.get().isActive();
    }

    /** Prefer the Authorization header (used by CLI / mobile clients); fall back to the
     *  httpOnly cookie set on browser logins. */
    private String extractToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7);
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (AUTH_COOKIE.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        return null;
    }
}
