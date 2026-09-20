package com.dataentry.service;

import com.dataentry.dto.UserDtos;
import com.dataentry.model.Role;
import com.dataentry.model.User;
import com.dataentry.repository.UserRepository;
import com.dataentry.security.TenantGuard;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TranslationService translator;
    private final Localizer localizer;
    private final AuditService audit;
    private final PasswordPolicy passwordPolicy;
    private final NotificationService notifications;
    private final MfaService mfaService;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       TranslationService translator,
                       Localizer localizer,
                       AuditService audit,
                       PasswordPolicy passwordPolicy,
                       NotificationService notifications,
                       MfaService mfaService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.translator = translator;
        this.localizer = localizer;
        this.audit = audit;
        this.passwordPolicy = passwordPolicy;
        this.notifications = notifications;
        this.mfaService = mfaService;
    }

    private void enforcePolicy(String password, String username) {
        var violations = passwordPolicy.validate(password, username);
        if (!violations.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    PasswordPolicy.describe(violations.get(0)));
        }
    }

    public List<UserDtos.UserResponse> list() {
        return userRepository.findAll().stream()
                .sorted(Comparator.comparing(User::getCreatedAt).reversed())
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public UserDtos.UserResponse create(UserDtos.CreateUserRequest req) {
        if (userRepository.existsByUsername(req.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        enforcePolicy(req.password(), req.username());
        User user = User.builder()
                .username(req.username())
                .passwordHash(passwordEncoder.encode(req.password()))
                .displayName(req.displayName())
                .email(req.email())
                .phone(req.phone())
                .role(Role.valueOf(req.role()))
                .active(true)
                .build();
        applyDisplayNameTranslation(user, req.displayName());
        User saved = userRepository.save(user);
        audit.record(AuditService.Action.CREATE, AuditService.EntityType.USER,
                saved.getId(), "username=" + saved.getUsername() + " role=" + saved.getRole());
        // Welcome notification lands in the new account's bell and links to the chat page.
        notifications.emit(saved, "WELCOME",
                "👋 " + (saved.getDisplayName() == null || saved.getDisplayName().isBlank()
                        ? saved.getUsername() : saved.getDisplayName())
                        + " — أهلاً بك! تواصل مع فريقك عبر الشات · Welcome! Reach your team via chat",
                "CHAT", null, null);
        return toDto(saved);
    }

    @Transactional
    public UserDtos.UserResponse update(Long id, UserDtos.UpdateUserRequest req) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        assertTeamManagedAccount(user);

        if (req.displayName() != null) {
            boolean changed = !Objects.equals(user.getDisplayName(), req.displayName());
            user.setDisplayName(req.displayName());
            if (changed) applyDisplayNameTranslation(user, req.displayName());
        }
        if (req.email() != null) user.setEmail(req.email());
        if (req.phone() != null) user.setPhone(req.phone());
        if (req.password() != null && !req.password().isBlank()) {
            enforcePolicy(req.password(), user.getUsername());
            user.setPasswordHash(passwordEncoder.encode(req.password()));
            user.setTokenVersion(user.getTokenVersion() + 1);
        }
        if (req.active() != null) user.setActive(req.active());
        User saved = userRepository.save(user);
        String details = "displayName=" + saved.getDisplayName()
                + " active=" + saved.isActive()
                + " passwordChanged=" + (req.password() != null && !req.password().isBlank());
        audit.record(AuditService.Action.UPDATE, AuditService.EntityType.USER, saved.getId(), details);
        return toDto(saved);
    }

    private void applyDisplayNameTranslation(User u, String displayName) {
        if (displayName == null || displayName.isBlank()) {
            u.setDisplayNameEn(null);
            u.setDisplayNameAr(null);
            return;
        }
        TranslationService.Bilingual bi = translator.toBoth(displayName);
        u.setDisplayNameEn(bi.en());
        u.setDisplayNameAr(bi.ar());
    }

    @Transactional
    public void delete(Long id, Long currentUserId) {
        if (id.equals(currentUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot delete your own account");
        }
        User u = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        assertTeamManagedAccount(u);
        userRepository.deleteById(id);
        audit.record(AuditService.Action.DELETE, AuditService.EntityType.USER, id, "username=" + u.getUsername());
    }

    /**
     * Operator reset: wipes the account's second factor and clears its lockout so the owner
     * can enroll a new device. Guarded by the same team-ownership check as an update; the
     * act itself is audited by MfaService as MFA_RESET.
     */
    @Transactional
    public UserDtos.UserResponse resetMfa(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        assertTeamManagedAccount(user);
        mfaService.reset(user);
        return toDto(user);
    }

    private void assertTeamManagedAccount(User user) {
        if (user.getRole() == Role.SUPER_ADMIN) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        TenantGuard.assertOwnership(user);
    }

    private UserDtos.UserResponse toDto(User u) {
        return new UserDtos.UserResponse(
                u.getId(),
                u.getUsername(),
                localizer.pick(u.getDisplayNameEn(), u.getDisplayNameAr(), u.getDisplayName()),
                u.getDisplayNameEn(),
                u.getDisplayNameAr(),
                u.getEmail(), u.getPhone(),
                u.getRole().name(), u.isActive(), u.isMfaEnabled(), u.getCreatedAt(),
                u.getAvatarUpdatedAt()
        );
    }
}
