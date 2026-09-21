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

@Service
public class NotificationService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final WebPushService push;

    public NotificationService(NotificationRepository repository, WebPushService push) {
        this.repository = repository;
        this.push = push;
    }

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
            dispatchPush(recipient, type, message, refType, refId, projectId);
        } catch (Exception e) {
            log.warn("Failed to emit notification (type={}, recipient={}): {}",
                    type, recipient.getId(), e.toString());
        }
    }

    /**
     * Browser push rides along with every in-app notification. Delivery only starts
     * after the surrounding transaction commits — a change that rolled back must not
     * reach anyone's screen — and it is fully asynchronous, so a slow push service can
     * never slow the request that produced the notification.
     */
    private void dispatchPush(User recipient, String type, String message,
                              String refType, Long refId, Long projectId) {
        if (push == null || !push.isEnabled() || recipient == null || recipient.getId() == null) return;
        String url = landingUrl(recipient, refType, refId, projectId);
        try {
            if (org.springframework.transaction.support.TransactionSynchronizationManager
                    .isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .registerSynchronization(new org.springframework.transaction.support
                                .TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                push.notifyUser(recipient.getId(), "Data Entry", message, url);
                            }
                        });
            } else {
                push.notifyUser(recipient.getId(), "Data Entry", message, url);
            }
        } catch (Exception e) {
            log.warn("Web Push dispatch skipped (type={}): {}", type, e.toString());
        }
    }

    /** Land the click somewhere useful; admins get the admin view of the same thing. */
    private String landingUrl(User recipient, String refType, Long refId, Long projectId) {
        boolean admin = recipient.isAdminLike();
        if ("ASSIGNMENT".equals(refType)) return admin ? "/admin/assignments" : "/assignments";
        // Deep-link into the exact conversation/group the message came from — the same
        // destination the in-app bell sends a click to.
        if ("CHAT".equals(refType)) return refId != null ? "/chat?c=" + refId : "/chat";
        if ("CHAT_GROUP".equals(refType)) return refId != null ? "/chat?g=" + refId : "/chat";
        if ("TICKET".equals(refType)) return admin ? "/admin/tickets" : "/my-tickets";
        if (projectId != null) {
            return (admin ? "/admin/project-folders/" : "/project-folders/") + projectId;
        }
        return admin ? "/admin" : "/dashboard";
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
