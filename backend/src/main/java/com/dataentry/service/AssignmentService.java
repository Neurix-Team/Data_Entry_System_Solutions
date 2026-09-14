package com.dataentry.service;

import com.dataentry.dto.AssignmentDtos;
import com.dataentry.model.Assignment;
import com.dataentry.model.AssignmentStatus;
import com.dataentry.model.Role;
import com.dataentry.model.User;
import com.dataentry.repository.AssignmentRepository;
import com.dataentry.repository.UserRepository;
import com.dataentry.security.TenantGuard;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Team-leader → agent work items. Leaders create, edit, reopen and delete inside their
 * team; agents see only what is assigned to them and flip it to DONE. Both directions
 * raise an in-app notification so the other side notices without polling the page.
 */
@Service
public class AssignmentService {

    public static final String NOTIFY_CREATED = "ASSIGNMENT_CREATED";
    public static final String NOTIFY_DONE = "ASSIGNMENT_DONE";
    public static final String REF_TYPE = "ASSIGNMENT";

    private final AssignmentRepository assignments;
    private final UserRepository users;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Localizer localizer;

    public AssignmentService(AssignmentRepository assignments,
                             UserRepository users,
                             NotificationService notifications,
                             AuditService audit,
                             Localizer localizer) {
        this.assignments = assignments;
        this.users = users;
        this.notifications = notifications;
        this.audit = audit;
        this.localizer = localizer;
    }

    // ---------------------------------------------------------------- team leader

    @Transactional(readOnly = true)
    public AssignmentDtos.ListResponse listForTeam() {
        List<AssignmentDtos.Response> items = assignments.findAllForTeam().stream()
                .map(this::toDto)
                .toList();
        return new AssignmentDtos.ListResponse(items, new AssignmentDtos.Summary(
                assignments.countByStatus(AssignmentStatus.OPEN),
                assignments.countByStatus(AssignmentStatus.DONE)));
    }

    @Transactional
    public AssignmentDtos.Response create(User current, AssignmentDtos.CreateRequest req) {
        User assignee = loadAssignee(req.assigneeId());
        Assignment a = Assignment.builder()
                .title(clean(req.title()))
                .description(blankToNull(req.description()))
                .dueDate(req.dueDate())
                .assignee(assignee)
                .assignedBy(current)
                .status(AssignmentStatus.OPEN)
                .createdAt(Instant.now())
                .build();
        Assignment saved = assignments.save(a);
        audit.record(AuditService.Action.CREATE, AuditService.EntityType.ASSIGNMENT, saved.getId(),
                "assignee=" + assignee.getUsername() + " title=" + saved.getTitle());
        notifyAssigned(saved, assignee);
        return toDto(saved);
    }

    @Transactional
    public AssignmentDtos.Response update(Long id, AssignmentDtos.UpdateRequest req) {
        Assignment a = loadForTeam(id);
        StringBuilder details = new StringBuilder();

        if (req.title() != null) {
            String title = clean(req.title());
            if (title.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title is required");
            }
            a.setTitle(title);
            details.append(" title=").append(title);
        }
        if (req.description() != null) {
            a.setDescription(blankToNull(req.description()));
        }
        if (req.dueDate() != null) {
            a.setDueDate(req.dueDate());
            details.append(" dueDate=").append(req.dueDate());
        } else if (Boolean.TRUE.equals(req.clearDueDate())) {
            a.setDueDate(null);
            details.append(" dueDate=none");
        }

        User newAssignee = null;
        if (req.assigneeId() != null && !req.assigneeId().equals(a.getAssignee().getId())) {
            newAssignee = loadAssignee(req.assigneeId());
            a.setAssignee(newAssignee);
            details.append(" assignee=").append(newAssignee.getUsername());
        }

        Assignment saved = assignments.save(a);
        audit.record(AuditService.Action.UPDATE, AuditService.EntityType.ASSIGNMENT, saved.getId(),
                details.toString().trim());
        if (newAssignee != null) notifyAssigned(saved, newAssignee);
        return toDto(saved);
    }

    @Transactional
    public AssignmentDtos.Response reopenByLeader(Long id) {
        Assignment a = loadForTeam(id);
        return toDto(reopen(a));
    }

    @Transactional
    public void delete(Long id) {
        Assignment a = loadForTeam(id);
        assignments.delete(a);
        audit.record(AuditService.Action.DELETE, AuditService.EntityType.ASSIGNMENT, id,
                "title=" + a.getTitle());
    }

    // ---------------------------------------------------------------- agent

    @Transactional(readOnly = true)
    public AssignmentDtos.ListResponse listForUser(User current) {
        requireUser(current);
        List<AssignmentDtos.Response> items = assignments.findAllForAssignee(current.getId()).stream()
                .map(this::toDto)
                .toList();
        return new AssignmentDtos.ListResponse(items, new AssignmentDtos.Summary(
                assignments.countByAssigneeIdAndStatus(current.getId(), AssignmentStatus.OPEN),
                assignments.countByAssigneeIdAndStatus(current.getId(), AssignmentStatus.DONE)));
    }

    @Transactional
    public AssignmentDtos.Response markDone(Long id, User current) {
        Assignment a = loadOwn(id, current);
        if (a.getStatus() != AssignmentStatus.DONE) {
            a.setStatus(AssignmentStatus.DONE);
            a.setCompletedAt(Instant.now());
            a = assignments.save(a);
            audit.record(AuditService.Action.STATUS_CHANGE, AuditService.EntityType.ASSIGNMENT,
                    a.getId(), "OPEN -> DONE");
            notifyDone(a, current);
        }
        return toDto(a);
    }

    @Transactional
    public AssignmentDtos.Response reopenByAssignee(Long id, User current) {
        Assignment a = loadOwn(id, current);
        return toDto(reopen(a));
    }

    // ---------------------------------------------------------------- internals

    private Assignment reopen(Assignment a) {
        if (a.getStatus() == AssignmentStatus.OPEN) return a;
        a.setStatus(AssignmentStatus.OPEN);
        a.setCompletedAt(null);
        Assignment saved = assignments.save(a);
        audit.record(AuditService.Action.STATUS_CHANGE, AuditService.EntityType.ASSIGNMENT,
                saved.getId(), "DONE -> OPEN");
        return saved;
    }

    private Assignment loadForTeam(Long id) {
        Assignment a = assignments.findWithPeopleById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found"));
        TenantGuard.assertOwnership(a);
        return a;
    }

    /** The assignee's own row, or 404 — never reveal that someone else's id exists. */
    private Assignment loadOwn(Long id, User current) {
        requireUser(current);
        Assignment a = assignments.findWithPeopleById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found"));
        TenantGuard.assertOwnership(a);
        if (a.getAssignee() == null || !Objects.equals(a.getAssignee().getId(), current.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found");
        }
        return a;
    }

    private User loadAssignee(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a team member");
        }
        User u = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Team member not found"));
        if (u.getRole() == Role.SUPER_ADMIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Team member not found");
        }
        try {
            TenantGuard.assertOwnership(u);
        } catch (ResponseStatusException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Team member not found");
        }
        if (!u.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That account is disabled");
        }
        return u;
    }

    private static void requireUser(User current) {
        if (current == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    private void notifyAssigned(Assignment a, User assignee) {
        boolean ar = localizer.currentLang() == TranslationService.Lang.AR;
        String message = ar
                ? "تكليف جديد ليك: \"" + a.getTitle() + "\""
                : "New assignment for you: \"" + a.getTitle() + "\"";
        notifications.emit(assignee, NOTIFY_CREATED, message, REF_TYPE, a.getId(), null);
    }

    private void notifyDone(Assignment a, User doneBy) {
        User leader = a.getAssignedBy();
        if (leader == null || Objects.equals(leader.getId(), doneBy.getId())) return;
        String who = displayName(doneBy);
        boolean ar = localizer.currentLang() == TranslationService.Lang.AR;
        String message = ar
                ? who + " خلّص التكليف \"" + a.getTitle() + "\""
                : who + " finished \"" + a.getTitle() + "\"";
        notifications.emit(leader, NOTIFY_DONE, message, REF_TYPE, a.getId(), null);
    }

    private String displayName(User u) {
        String picked = localizer.pick(u.getDisplayNameEn(), u.getDisplayNameAr(), u.getDisplayName());
        return picked == null || picked.isBlank() ? u.getUsername() : picked;
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private AssignmentDtos.Person person(User u) {
        if (u == null) return null;
        return new AssignmentDtos.Person(
                u.getId(),
                u.getUsername(),
                localizer.pick(u.getDisplayNameEn(), u.getDisplayNameAr(), u.getDisplayName()),
                u.getDisplayNameEn(),
                u.getDisplayNameAr(),
                u.getAvatarUpdatedAt());
    }

    private AssignmentDtos.Response toDto(Assignment a) {
        return new AssignmentDtos.Response(
                a.getId(),
                a.getTitle(),
                a.getDescription(),
                a.getStatus().name(),
                a.getDueDate(),
                person(a.getAssignee()),
                person(a.getAssignedBy()),
                a.getCreatedAt(),
                a.getCompletedAt());
    }
}
