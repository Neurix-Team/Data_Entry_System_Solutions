package com.dataentry.service;

import com.dataentry.dto.NotificationDtos;
import com.dataentry.model.Notification;
import com.dataentry.model.User;
import com.dataentry.repository.NotificationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * In-app notification pipeline. Emitted server-side on events (e.g. ticket approval);
 * consumed by the frontend via the bell widget on the topbar.
 *
 * <p>Emit is intentionally best-effort: an approval action should not fail because the
 * notification write failed. The caller passes the notification through {@link #emit},
 * and any exception is logged but swallowed — the primary action is what mattered.
 */
@Service
public class NotificationService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    /**
     * Emit a notification for {@code recipient}. Runs in its own transaction (via a fresh
     * @Transactional) so a rollback of the caller's transaction doesn't kill the
     * notification write, and vice versa.
     */
    @Transactional
    public void emit(User recipient, String type, String message,
                     String refType, Long refId, Long projectId) {
        if (recipient == null || type == null || message == null) return;
        try {
            Notification n = Notification.builder()
                    .recipient(recipient)
                    .type(type)
                    .message(message.length() > 500 ? message.substring(0, 500) : message)
                    .refType(refType)
                    .refId(refId)
                    .projectId(projectId)
                    .createdAt(Instant.now())
                    .build();
            repository.save(n);
        } catch (Exception e) {
            // Never let a notification write bring down the primary action.
            log.warn("Failed to emit notification (type={}, recipient={}): {}",
                    type, recipient.getId(), e.toString());
        }
    }

    @Transactional(readOnly = true)
    public NotificationDtos.Feed list(User user) {
        if (user == null) return new NotificationDtos.Feed(List.of(), 0);
        List<Notification> rows = repository.findAllByRecipientIdOrderByCreatedAtDesc(user.getId());
        long unread = repository.countByRecipientIdAndReadAtIsNull(user.getId());
        List<NotificationDtos.Item> items = rows.stream()
                .map(this::toDto)
                .toList();
        return new NotificationDtos.Feed(items, unread);
    }

    @Transactional
    public NotificationDtos.Item markRead(User user, Long id) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Notification n = repository.findByIdAndRecipientId(id, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found"));
        if (n.getReadAt() == null) {
            n.setReadAt(Instant.now());
            repository.save(n);
        }
        return toDto(n);
    }

    @Transactional
    public int markAllRead(User user) {
        if (user == null) return 0;
        return repository.markAllRead(user.getId(), Instant.now());
    }

    private NotificationDtos.Item toDto(Notification n) {
        return new NotificationDtos.Item(
                n.getId(),
                n.getType(),
                n.getMessage(),
                n.getRefType(),
                n.getRefId(),
                n.getProjectId(),
                n.getCreatedAt(),
                n.getReadAt()
        );
    }
}
