package com.dataentry.service;

import com.dataentry.dto.UserDtos;
import com.dataentry.model.Role;
import com.dataentry.model.User;
import com.dataentry.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TranslationService translator;
    private final Localizer localizer;
    private final AuditService audit;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       TranslationService translator,
                       Localizer localizer,
                       AuditService audit) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.translator = translator;
        this.localizer = localizer;
        this.audit = audit;
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
        return toDto(saved);
    }

    @Transactional
    public UserDtos.UserResponse update(Long id, UserDtos.UpdateUserRequest req) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (req.displayName() != null) {
            boolean changed = !Objects.equals(user.getDisplayName(), req.displayName());
            user.setDisplayName(req.displayName());
            if (changed) applyDisplayNameTranslation(user, req.displayName());
        }
        if (req.email() != null) user.setEmail(req.email());
        if (req.phone() != null) user.setPhone(req.phone());
        if (req.password() != null && !req.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(req.password()));
        }
        if (req.active() != null) user.setActive(req.active());
        User saved = userRepository.save(user);
        // Record whether the password/active flag changed — but never log the value itself.
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
        userRepository.deleteById(id);
        audit.record(AuditService.Action.DELETE, AuditService.EntityType.USER, id, "username=" + u.getUsername());
    }

    private UserDtos.UserResponse toDto(User u) {
        return new UserDtos.UserResponse(
                u.getId(),
                u.getUsername(),
                localizer.pick(u.getDisplayNameEn(), u.getDisplayNameAr(), u.getDisplayName()),
                u.getDisplayNameEn(),
                u.getDisplayNameAr(),
                u.getEmail(), u.getPhone(),
                u.getRole().name(), u.isActive(), u.getCreatedAt()
        );
    }
}
