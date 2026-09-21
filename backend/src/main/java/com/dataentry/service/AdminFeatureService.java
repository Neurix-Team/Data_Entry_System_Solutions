package com.dataentry.service;

import com.dataentry.dto.AdminFeatureDtos;
import com.dataentry.model.Announcement;
import com.dataentry.model.AssignmentStatus;
import com.dataentry.model.Role;
import com.dataentry.model.Team;
import com.dataentry.model.User;
import com.dataentry.repository.AnnouncementRepository;
import com.dataentry.repository.AssignmentRepository;
import com.dataentry.repository.TeamRepository;
import com.dataentry.repository.TicketRepository;
import com.dataentry.repository.UserRepository;
import com.dataentry.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin feature pack: announcements (C2), workload balancing (C3), weekly report
 * (C4) and per-agent data quality (C5). Team scoping matches the recycle bin:
 * null teamId = no tenant restriction (super admin views); team-only operations
 * refuse to guess when there is no team context.
 */
@Service
public class AdminFeatureService {

    private static final Logger log = LoggerFactory.getLogger(AdminFeatureService.class);

    private final AnnouncementRepository announcements;
    private final AssignmentRepository assignments;
    private final TicketRepository tickets;
    private final UserRepository users;
    private final TeamRepository teams;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;
    private final boolean weeklyScheduleEnabled;

    public AdminFeatureService(AnnouncementRepository announcements,
                               AssignmentRepository assignments,
                               TicketRepository tickets,
                               UserRepository users,
                               TeamRepository teams,
                               NotificationService notifications,
                               AuditService audit,
                               Clock clock,
                               @Value("${app.reports.weekly-schedule-enabled:true}") boolean weeklyScheduleEnabled) {
        this.announcements = announcements;
        this.assignments = assignments;
        this.tickets = tickets;
        this.users = users;
        this.teams = teams;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
        this.weeklyScheduleEnabled = weeklyScheduleEnabled;
    }

    private Long teamScope() {
        return TenantContext.isSuperAdmin() ? null : TenantContext.getTeamId();
    }

    private void requireTeamScope(String what) {
        if (teamScope() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    what + " belongs to a team — enter one first.");
        }
    }

    // ── C2: announcements ─────────────────────────────────────────────────────

    @Transactional
    public AdminFeatureDtos.AnnouncementResponse announce(User current,
                                                           AdminFeatureDtos.CreateAnnouncementRequest req) {
        requireTeamScope("Announcements");
        Long teamId = teamScope();
        Team team = teams.findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
        Announcement saved = announcements.save(Announcement.builder()
                .team(team).createdBy(current)
                .title(req.title().trim()).body(req.body().trim())
                .audience(req.audience()).createdAt(Instant.now(clock))
                .build());
        List<User> recipients = audienceRecipients(teamId, saved.getAudience());
        String message = saved.getTitle() + " — " + saved.getBody();
        for (User recipient : recipients) {
            notifications.emit(recipient, "ANNOUNCEMENT", message,
                    "ANNOUNCEMENT", saved.getId(), null);
        }
        audit.record(AuditService.Action.CREATE, AuditService.EntityType.ANNOUNCEMENT,
                saved.getId(), "audience=" + saved.getAudience() + " recipients=" + recipients.size());
        return toDto(saved);
    }

    private List<User> audienceRecipients(Long teamId, String audience) {
        return switch (audience) {
            case "USERS" -> activeOf(users.findAllByTeamIdAndRoleOrderByCreatedAtAsc(teamId, Role.USER));
            case "ADMINS" -> activeOf(users.findAllByTeamIdAndRoleOrderByCreatedAtAsc(teamId, Role.ADMIN));
            default -> activeOf(users.findAllByTeamIdOrderByCreatedAtDesc(teamId));
        };
    }

    private List<User> activeOf(List<User> list) {
        return list.stream().filter(User::isActive).toList();
    }

    @Transactional(readOnly = true)
    public List<AdminFeatureDtos.AnnouncementResponse> listAnnouncements() {
        return announcements.findTop20ByOrderByCreatedAtDesc().stream().map(this::toDto).toList();
    }

    private AdminFeatureDtos.AnnouncementResponse toDto(Announcement a) {
        User author = a.getCreatedBy();
        return new AdminFeatureDtos.AnnouncementResponse(a.getId(), a.getTitle(), a.getBody(),
                a.getAudience(), a.getCreatedAt(), author == null ? null : displayName(author));
    }

    private String displayName(User u) {
        return u.getDisplayName() != null && !u.getDisplayName().isBlank()
                ? u.getDisplayName() : u.getUsername();
    }

    // ── C3: workload balancing ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AdminFeatureDtos.WorkloadResponse workload() {
        Long teamId = teamScope();
        List<User> agents = teamId == null ? List.of()
                : activeOf(users.findAllByTeamIdAndRoleOrderByCreatedAtAsc(teamId, Role.USER));
        if (agents.isEmpty()) {
            return new AdminFeatureDtos.WorkloadResponse(List.of(), null, null);
        }
        Map<Long, Long> openAssignments = new HashMap<>();
        assignments.findAllForTeam().stream()
                .filter(a -> a.getStatus() == AssignmentStatus.OPEN)
                .forEach(a -> openAssignments.merge(a.getAssignee().getId(), 1L, Long::sum));

        Map<Long, Map<String, Long>> week = new HashMap<>();
        for (TicketRepository.UserStatusCountRow row : tickets.statusCountsByUserSince(teamId, weekStart())) {
            week.computeIfAbsent(row.getUserId(), k -> new HashMap<>())
                    .put(row.getStatus(), row.getTotal());
        }

        List<AdminFeatureDtos.WorkloadRow> rows = new ArrayList<>(agents.size());
        for (User agent : agents) {
            Map<String, Long> perStatus = week.getOrDefault(agent.getId(), Map.of());
            long weekEntries = perStatus.values().stream().mapToLong(Long::longValue).sum();
            long weekCompleted = perStatus.getOrDefault("COMPLETED", 0L);
            long open = openAssignments.getOrDefault(agent.getId(), 0L);
            rows.add(new AdminFeatureDtos.WorkloadRow(agent.getId(), displayName(agent),
                    agent.getUsername(), open, weekEntries, weekCompleted, open * 3 + weekEntries));
        }
        rows.sort(Comparator.comparingLong(AdminFeatureDtos.WorkloadRow::loadScore).reversed());

        Long busiest = null;
        Long freest = null;
        if (!rows.isEmpty()) {
            long top = rows.get(0).loadScore();
            long bottom = rows.get(rows.size() - 1).loadScore();
            if (top > 0) busiest = rows.get(0).userId();
            if (bottom < top) freest = rows.get(rows.size() - 1).userId();
        }
        return new AdminFeatureDtos.WorkloadResponse(rows, busiest, freest);
    }

    private Instant weekStart() {
        return LocalDate.now(clock).minusDays(6)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    // ── C4: weekly report ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AdminFeatureDtos.WeeklyReport weekly() {
        requireTeamScope("The weekly report");
        Long teamId = teamScope();
        Instant to = Instant.now(clock);
        Instant from = weekStart();
        List<AdminFeatureDtos.WeeklyDayRow> byDay = new ArrayList<>(7);
        long total = 0;
        long completed = 0;
        for (TicketRepository.WeeklySummaryProjection row
                : tickets.weeklySummary(teamId, from, ZoneId.systemDefault().getId())) {
            byDay.add(new AdminFeatureDtos.WeeklyDayRow(row.getDay().toString(), row.getTotal(), row.getCompleted()));
            total += row.getTotal();
            completed += row.getCompleted();
        }
        long completionRatePct = total == 0 ? 0 : Math.round(completed * 100.0 / total);
        List<AdminFeatureDtos.WeeklyPerformer> top = tickets
                .leaderboardAggregate(teamId, from, from, from).stream()
                .limit(3)
                .map(r -> new AdminFeatureDtos.WeeklyPerformer(r.getUserId(),
                        r.getDisplayName() != null && !r.getDisplayName().isBlank()
                                ? r.getDisplayName() : r.getUsername(),
                        r.getTotal()))
                .toList();
        return new AdminFeatureDtos.WeeklyReport(from, to, total, completed, completionRatePct, byDay, top);
    }

    /** Push the weekly summary to every team leader (in-app + browser push). */
    public AdminFeatureDtos.DispatchResult dispatchWeekly() {
        requireTeamScope("The weekly report");
        AdminFeatureDtos.WeeklyReport report = weekly();
        List<User> admins = activeOf(users
                .findAllByTeamIdAndRoleOrderByCreatedAtAsc(teamScope(), Role.ADMIN));
        String message = "Weekly report: " + report.totalEntries() + " entries, "
                + report.completedEntries() + " completed (" + report.completionRatePct() + "%).";
        for (User admin : admins) {
            notifications.emit(admin, "WEEKLY_REPORT", message, null, null, null);
        }
        log.info("Weekly report dispatched to {} admin(s) of team #{}.", admins.size(), teamScope());
        return new AdminFeatureDtos.DispatchResult(admins.size());
    }

    /**
     * Monday 06:00 local: every team's leaders get the digest without anyone
     * remembering to ask for it. Each team runs in its own tenant context.
     */
    @Scheduled(cron = "${app.reports.weekly-cron:0 0 6 * * MON}")
    public void dispatchWeeklyToAllTeams() {
        if (!weeklyScheduleEnabled) return;
        for (Team team : teams.findAll()) {
            TenantContext.set(team.getId(), Role.ADMIN, null, null);
            try {
                dispatchWeekly();
            } catch (Exception e) {
                log.warn("Weekly report for team #{} failed: {}", team.getId(), e.toString());
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ── C5: per-agent data quality ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AdminFeatureDtos.QualityResponse quality() {
        Long teamId = teamScope();
        List<User> agents = teamId == null ? List.of()
                : activeOf(users.findAllByTeamIdAndRoleOrderByCreatedAtAsc(teamId, Role.USER));
        if (agents.isEmpty()) {
            return new AdminFeatureDtos.QualityResponse(List.of());
        }
        Map<Long, Map<String, Long>> allTime = new HashMap<>();
        for (TicketRepository.UserStatusCountRow row : tickets.statusCountsByUser(teamId)) {
            allTime.computeIfAbsent(row.getUserId(), k -> new HashMap<>())
                    .put(row.getStatus(), row.getTotal());
        }
        Map<Long, Long> weekTotal = new HashMap<>();
        for (TicketRepository.UserStatusCountRow row : tickets.statusCountsByUserSince(teamId, weekStart())) {
            weekTotal.merge(row.getUserId(), row.getTotal(), Long::sum);
        }

        List<AdminFeatureDtos.QualityRow> rows = new ArrayList<>(agents.size());
        for (User agent : agents) {
            Map<String, Long> perStatus = allTime.getOrDefault(agent.getId(), Map.of());
            long total = perStatus.values().stream().mapToLong(Long::longValue).sum();
            long completed = perStatus.getOrDefault("COMPLETED", 0L);
            long review = perStatus.getOrDefault("REVIEW", 0L);
            long inProgress = perStatus.getOrDefault("IN_PROGRESS", 0L);
            rows.add(new AdminFeatureDtos.QualityRow(agent.getId(), displayName(agent), agent.getUsername(),
                    total, completed, review, inProgress,
                    total == 0 ? 0 : Math.round(completed * 1000.0 / total) / 10.0,
                    total == 0 ? 0 : Math.round(review * 1000.0 / total) / 10.0,
                    weekTotal.getOrDefault(agent.getId(), 0L)));
        }
        // Worst first: highest review rate, then lowest completion rate.
        rows.sort(Comparator
                .comparingDouble(AdminFeatureDtos.QualityRow::reviewRate).reversed()
                .thenComparingDouble(AdminFeatureDtos.QualityRow::completedRate));
        return new AdminFeatureDtos.QualityResponse(rows);
    }
}