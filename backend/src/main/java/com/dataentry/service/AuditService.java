package com.dataentry.service;

import com.dataentry.model.AuditLog;
import com.dataentry.model.User;
import com.dataentry.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around {@link AuditLogRepository}: pulls the acting user out of the security
 * context so callers don't have to plumb the principal into every service call.  Log calls
 * are best-effort — a persistence failure is logged and swallowed rather than rolling back the
 * business action that just succeeded.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public static final class EntityType {
        public static final String DEPARTMENT = "DEPARTMENT";
        public static final String SUBCATEGORY = "SUBCATEGORY";
        public static final String CUSTOM_FIELD = "CUSTOM_FIELD";
        public static final String PROJECT = "PROJECT";
        public static final String USER = "USER";
        public static final String TICKET = "TICKET";
        private EntityType() {}
    }

    public static final class Action {
        public static final String CREATE = "CREATE";
        public static final String UPDATE = "UPDATE";
        public static final String DELETE = "DELETE";
        public static final String STATUS_CHANGE = "STATUS_CHANGE";
        private Action() {}
    }

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(String action, String entityType, Long entityId, String details) {
        try {
            User actor = currentActor();
            AuditLog entry = AuditLog.builder()
                    .actorId(actor == null ? null : actor.getId())
                    .actorUsername(actor == null ? null : actor.getUsername())
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .details(truncate(details))
                    .build();
            repository.save(entry);
        } catch (Exception e) {
            log.warn("Failed to write audit log ({} {} #{}): {}", action, entityType, entityId, e.getMessage());
        }
    }

    private User currentActor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object p = auth.getPrincipal();
        return p instanceof User u ? u : null;
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
