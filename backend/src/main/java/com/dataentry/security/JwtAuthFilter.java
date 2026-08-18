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

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    /** Header used by a SUPER_ADMIN to "enter" a specific team from the super-admin UI. */
    public static final String IMPERSONATE_HEADER = "X-Impersonate-Team-Id";

    /** Name of the httpOnly cookie used for browser sessions. Kept in sync with AuthController. */
    public static final String AUTH_COOKIE = "dems_auth";

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

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);
        try {
            if (token != null) {
                try {
                    Claims claims = jwtService.parse(token);
                    String username = claims.getSubject();
                    if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                        Optional<User> userOpt = userRepository.findByUsername(username);
                        if (userOpt.isPresent() && userOpt.get().isActive()) {
                            authenticate(request, userOpt.get(), claims);
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

    private void authenticate(HttpServletRequest request, User user, Claims claims) {
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
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        if (user.getRole() == Role.SUPER_ADMIN) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        } else if (user.getRole() == Role.ADMIN) {
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        }

        TenantContext.set(teamId, role, user.getId(), impersonatedBy);

        var authToken = new UsernamePasswordAuthenticationToken(user, null, authorities);
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
