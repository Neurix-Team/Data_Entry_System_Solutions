package com.dataentry.service;

import com.dataentry.dto.AuthDtos;
import com.dataentry.model.Role;
import com.dataentry.model.Team;
import com.dataentry.model.User;
import com.dataentry.repository.UserRepository;
import com.dataentry.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthDtos.LoginResponse login(AuthDtos.LoginRequest req) {
        User user = userRepository.findByUsername(req.username())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account disabled");
        }

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        // Non-super users without a team are a data-consistency bug (either seed forgot to
        // set one, or a migration left a row orphaned). Refuse rather than silently issuing
        // an unfilterable token.
        if (user.getRole() != Role.SUPER_ADMIN && user.getTeam() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Account is not attached to a team. Contact your administrator.");
        }

        Long teamId = user.getTeam() != null ? user.getTeam().getId() : null;
        String token = jwtService.generateToken(user.getUsername(), user.getRole().name(),
                user.getId(), teamId);

        return new AuthDtos.LoginResponse(token, jwtService.getExpirationMs(), toDto(user, false));
    }

    /** Build the {@code /auth/me} payload — used both after login and on session probe. */
    public static AuthDtos.UserDto toDto(User user, boolean impersonating) {
        return new AuthDtos.UserDto(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getRole().name(),
                user.getAvatarUpdatedAt(),
                teamRef(user.getTeam()),
                impersonating
        );
    }

    public static AuthDtos.TeamRef teamRef(Team t) {
        if (t == null) return null;
        return new AuthDtos.TeamRef(t.getId(), t.getSlug(), t.getName(),
                t.getNameEn(), t.getNameAr(), t.getColor());
    }
}
