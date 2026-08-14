package com.dataentry.controller;

import com.dataentry.model.User;
import com.dataentry.model.UserAvatar;
import com.dataentry.repository.UserAvatarRepository;
import com.dataentry.repository.UserRepository;
import com.dataentry.service.AuditService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Endpoints for reading, uploading, and removing a user's profile picture.
 *
 * <p>Public read via {@code GET /api/users/{id}/avatar} — returns the image bytes with the
 * stored MIME type, or 404 if the user has no avatar. Auth-protected write endpoints operate
 * on the currently authenticated user only.</p>
 */
@RestController
public class UserAvatarController {

    private static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024; // 2 MB
    private static final List<String> ALLOWED_TYPES = List.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif"
    );

    private final UserRepository userRepository;
    private final UserAvatarRepository avatarRepository;
    private final AuditService audit;

    public UserAvatarController(UserRepository userRepository,
                                UserAvatarRepository avatarRepository,
                                AuditService audit) {
        this.userRepository = userRepository;
        this.avatarRepository = avatarRepository;
        this.audit = audit;
    }

    // ---------- Serve ----------

    @GetMapping("/api/users/{id}/avatar")
    public ResponseEntity<byte[]> serve(@PathVariable Long id) {
        Optional<UserAvatar> opt = avatarRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        UserAvatar a = opt.get();
        MediaType type;
        try {
            type = MediaType.parseMediaType(a.getContentType());
        } catch (Exception e) {
            type = MediaType.IMAGE_PNG;
        }
        // Short cache — the URL is cache-busted with ?v={updatedAt}, so browsers pick up new
        // uploads on their next render regardless.
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(a.getData());
    }

    // ---------- Upload (self) ----------

    @PostMapping(value = "/api/user/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AvatarInfo> upload(@RequestParam("file") MultipartFile file,
                                             @AuthenticationPrincipal User me) throws IOException {
        if (me == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file uploaded");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Image must be under 2 MB");
        }
        String type = file.getContentType();
        if (type == null || !ALLOWED_TYPES.contains(type.toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Only PNG, JPEG, WEBP, or GIF images are allowed");
        }

        Instant now = Instant.now();
        UserAvatar avatar = avatarRepository.findById(me.getId()).orElseGet(UserAvatar::new);
        avatar.setUserId(me.getId());
        avatar.setContentType(type);
        avatar.setData(file.getBytes());
        avatar.setUpdatedAt(now);
        avatarRepository.save(avatar);

        me.setAvatarUpdatedAt(now);
        userRepository.save(me);

        audit.record(AuditService.Action.UPDATE, AuditService.EntityType.USER, me.getId(),
                "avatar uploaded");

        return ResponseEntity.ok(new AvatarInfo(me.getId(), now));
    }

    // ---------- Delete (self) ----------

    @DeleteMapping("/api/user/me/avatar")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal User me) {
        if (me == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        avatarRepository.deleteById(me.getId());
        me.setAvatarUpdatedAt(null);
        userRepository.save(me);
        audit.record(AuditService.Action.UPDATE, AuditService.EntityType.USER, me.getId(),
                "avatar removed");
        return ResponseEntity.noContent().build();
    }

    public record AvatarInfo(Long userId, Instant updatedAt) {}
}
