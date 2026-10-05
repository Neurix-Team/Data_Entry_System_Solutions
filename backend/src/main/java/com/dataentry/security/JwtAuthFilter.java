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
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    public static final String IMPERSONATE_HEADER = "X-Impersonate-Team-Id";

    public static final String AUTH_COOKIE = "dems_auth";

    private record AuthEntry(User user, Long teamId, Role effectiveRole,
                                  Long impersonatedBy, List<SimpleGrantedAuthority> authorities) {}

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final RequestAttributeSecurityContextRepository contextRepository = new RequestAttributeSecurityContextRepository();

    public JwtAuthFilter(JwtService jwtService,
                         UserRepository userRepository,
                         TeamRepository teamRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
    }


    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);
        try {
            if (token != null) {
                try {
                    // Revalidate identity, revocation and team state on every request.
                    Claims claims = jwtService.parse(token);
                    String username = claims.getSubject();
                    if (username != null && claims.getExpiration() != null
                            && SecurityContextHolder.getContext().getAuthentication() == null) {
                        Optional<User> userOpt = userRepository.findByUsername(username);
                        if (userOpt.isPresent()) {
                            User user = userOpt.get();
                            Object uid = claims.get("uid");
                            boolean identityMatches = uid instanceof Number n
                                    && user.getId() != null && n.longValue() == user.getId();
                            boolean teamActive = user.getRole() == Role.SUPER_ADMIN
                                    || (user.getTeam() != null && user.getTeam().isActive());
                                                        if (identityMatches && user.isActive() && teamActive
                                    && tokenVersionCurrent(claims, user)) {
                                if (mfaSatisfied(claims, user, request)) {
                                    AuthEntry entry = buildEntry(request, user);
                                    if (entry != null) {
                                        applyAuth(request, entry);
                                        contextRepository.saveContext(SecurityContextHolder.getContext(), request, response);
                                    }
                                }
                            }
                        }
                    }
                } catch (JwtException e) {
                    log.debug("Rejected JWT for {} {}: {}",
                            request.getMethod(), request.getRequestURI(), e.getMessage());
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private AuthEntry buildEntry(HttpServletRequest request, User user) {
        Role role = user.getRole();
        Long teamId = user.getTeam() != null ? user.getTeam().getId() : null;
        Long impersonatedBy = null;

        if (role == Role.SUPER_ADMIN) {
            Long headerTeam = parseHeaderTeamId(request);
            if (request.getHeader(IMPERSONATE_HEADER) != null
                    && (headerTeam == null || !teamExists(headerTeam))) return null;
            if (headerTeam != null) {
                teamId = headerTeam;
                impersonatedBy = user.getId();
                role = Role.ADMIN;
            }
        }

        List<SimpleGrantedAuthority> authorities = new ArrayList<>(3);
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        if (user.getRole() == Role.SUPER_ADMIN) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        } else if (user.getRole() == Role.ADMIN) {
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        }

        return new AuthEntry(
                user,
                teamId,
                role,
                impersonatedBy,
                List.copyOf(authorities)
        );
    }

    private void applyAuth(HttpServletRequest request, AuthEntry entry) {
        if (SecurityContextHolder.getContext().getAuthentication() != null) return;
        TenantContext.set(entry.teamId(), entry.effectiveRole(),
                entry.user().getId(), entry.impersonatedBy());
        var authToken = new UsernamePasswordAuthenticationToken(
                entry.user(), null, entry.authorities());
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }

        private boolean tokenVersionCurrent(Claims claims, User user) {
        Object tv = claims.get("tv");
        long claimed = 0L;
        if (tv instanceof Number n) {
            claimed = n.longValue();
        }
        if (claimed == user.getTokenVersion()) return true;
        log.debug("Rejected stale JWT for user={} (claim tv={}, current tv={})",
                user.getUsername(), claimed, user.getTokenVersion());
        return false;
    }

    /**
     * A token carrying {@code mfa_pending == true} proves the password but not the second
     * factor, so it is honoured on exactly the routes the challenge needs: finishing the
     * challenge, reading MFA state, signing out, and — for an account whose device is not
     * confirmed yet — first-run enrollment. An already-enrolled account can never re-enroll
     * or disable on a pending ticket, which is what stops a password-only attacker from
     * swapping the victim's factor for their own. A fully authenticated token passes
     * unconditionally.
     */
    private boolean mfaSatisfied(Claims claims, User user, HttpServletRequest request) {
        if (!Boolean.TRUE.equals(claims.get(JwtService.MFA_PENDING_CLAIM, Boolean.class))) {
            return true;
        }
        String path = request.getRequestURI();
        if (path == null) return false;
        String method = request.getMethod();
        boolean verify = "POST".equals(method) && "/api/auth/mfa/verify".equals(path);
        boolean status = "GET".equals(method) && "/api/auth/mfa/status".equals(path);
        boolean logout = "POST".equals(method) && "/api/auth/logout".equals(path);
        boolean me = "GET".equals(method) && "/api/auth/me".equals(path);
        boolean firstRunEnrollment = "POST".equals(method)
                && ("/api/auth/mfa/enroll".equals(path)
                        || "/api/auth/mfa/enroll/confirm".equals(path))
                && user != null
                && !user.isMfaEnabled();
        boolean allowed = verify || status || logout || me || firstRunEnrollment;
        if (!allowed) {
            log.debug("Blocked {} {} for an MFA-pending token.", method, path);
        }
        return allowed;
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
        Optional<Team> t = teamRepository.findById(id);
        return t.isPresent() && t.get().isActive();
    }

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
