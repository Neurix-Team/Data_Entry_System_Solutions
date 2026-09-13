package com.dataentry.service;

import com.dataentry.dto.SuperAdminDtos;
import com.dataentry.model.Role;
import com.dataentry.model.Team;
import com.dataentry.model.User;
import com.dataentry.repository.TeamRepository;
import com.dataentry.repository.UserRepository;
import com.dataentry.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class SuperAdminService {

    private final Clock clock;
    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TranslationService translator;
    private final JdbcTemplate jdbc;

    public SuperAdminService(Clock clock,
                             TeamRepository teamRepository,
                             UserRepository userRepository,
                             PasswordEncoder passwordEncoder,
                             TranslationService translator,
                             JdbcTemplate jdbc) {
        this.clock = clock;
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.translator = translator;
        this.jdbc = jdbc;
    }


    public SuperAdminDtos.OverviewStats overview() {
        List<Team> teams = teamRepository.findAllByOrderByCreatedAtAsc();
        Map<Long, long[]> perTeam = loadPerTeamCounts();

        LocalDate today = LocalDate.now(clock);
        Instant startOfToday = today.atStartOfDay(clock.getZone()).toInstant();
        Instant startOfWeek = today.minusDays(6).atStartOfDay(clock.getZone()).toInstant();
        Map<Long, Long> ticketsThisWeek = weeklyTicketCounts(startOfWeek);

        List<SuperAdminDtos.TeamSummary> summaries = teams.stream()
                .map(t -> toSummary(t, perTeam.getOrDefault(t.getId(), new long[5]),
                        ticketsThisWeek.getOrDefault(t.getId(), 0L)))
                .toList();

        long totalUsers = summaries.stream().mapToLong(SuperAdminDtos.TeamSummary::userCount).sum();
        long totalAdmins = summaries.stream().mapToLong(SuperAdminDtos.TeamSummary::adminCount).sum();
        long totalProjects = summaries.stream().mapToLong(SuperAdminDtos.TeamSummary::projectCount).sum();
        long totalDepartments = summaries.stream().mapToLong(SuperAdminDtos.TeamSummary::departmentCount).sum();
        long totalTickets = summaries.stream().mapToLong(SuperAdminDtos.TeamSummary::ticketCount).sum();
        long weekTotal = summaries.stream().mapToLong(SuperAdminDtos.TeamSummary::ticketsThisWeek).sum();
        Long ticketsToday = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE submitted_at >= ?",
                Long.class, Timestamp.from(startOfToday));

        return new SuperAdminDtos.OverviewStats(
                teams.size(),
                (int) teams.stream().filter(Team::isActive).count(),
                totalUsers, totalAdmins, totalProjects, totalDepartments,
                totalTickets, ticketsToday == null ? 0L : ticketsToday, weekTotal,
                summaries
        );
    }

    public List<SuperAdminDtos.TeamSummary> listTeams() {
        return overview().teams();
    }

    private Map<Long, long[]> loadPerTeamCounts() {
        Map<Long, long[]> out = new HashMap<>();
        loadInto(out, 0, "SELECT team_id, COUNT(*) FROM users WHERE team_id IS NOT NULL GROUP BY team_id");
        loadInto(out, 1, "SELECT team_id, COUNT(*) FROM users WHERE team_id IS NOT NULL AND role = 'ADMIN' GROUP BY team_id");
        loadInto(out, 2, "SELECT team_id, COUNT(*) FROM projects WHERE team_id IS NOT NULL GROUP BY team_id");
        loadInto(out, 3, "SELECT team_id, COUNT(*) FROM departments WHERE team_id IS NOT NULL GROUP BY team_id");
        loadInto(out, 4, "SELECT team_id, COUNT(*) FROM tickets WHERE team_id IS NOT NULL GROUP BY team_id");
        return out;
    }

    private void loadInto(Map<Long, long[]> map, int idx, String sql) {
        try {
            jdbc.query(sql, rs -> {
                long teamId = rs.getLong(1);
                long count = rs.getLong(2);
                map.computeIfAbsent(teamId, k -> new long[5])[idx] = count;
            });
        } catch (Exception ignored) {
        }
    }

    private Map<Long, Long> weeklyTicketCounts(Instant since) {
        Map<Long, Long> out = new HashMap<>();
        try {
            jdbc.query(
                    "SELECT team_id, COUNT(*) FROM tickets " +
                            "WHERE team_id IS NOT NULL AND submitted_at >= ? GROUP BY team_id",
                    ps -> ps.setTimestamp(1, Timestamp.from(since)),
                    rs -> { out.put(rs.getLong(1), rs.getLong(2)); }
            );
        } catch (Exception ignored) {}
        return out;
    }

    private SuperAdminDtos.TeamSummary toSummary(Team t, long[] counts, long weekCount) {
        return new SuperAdminDtos.TeamSummary(
                t.getId(), t.getSlug(), t.getName(), t.getNameEn(), t.getNameAr(),
                t.getDescription(), t.getColor(), t.isActive(), t.getCreatedAt(),
                counts[0], counts[1], counts[2], counts[3], counts[4], weekCount
        );
    }


    @Transactional
    public SuperAdminDtos.TeamSummary createTeam(SuperAdminDtos.CreateTeamRequest req) {
        String slug = req.slug().toLowerCase();
        if (teamRepository.existsBySlugIgnoreCase(slug)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A team with this slug already exists.");
        }
        TranslationService.Bilingual bi = translator.toBoth(req.name());
        Team saved = teamRepository.save(Team.builder()
                .slug(slug)
                .name(req.name())
                .nameEn(bi.en())
                .nameAr(bi.ar())
                .description(req.description())
                .color(req.color() != null ? req.color() : "#6366f1")
                .createdById(TenantContext.getUserId())
                .active(true)
                .build());
        return toSummary(saved, new long[5], 0);
    }

    @Transactional
    public SuperAdminDtos.TeamSummary updateTeam(Long id, SuperAdminDtos.UpdateTeamRequest req) {
        Team team = teamRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
        team.setName(req.name());
        TranslationService.Bilingual bi = translator.toBoth(req.name());
        team.setNameEn(bi.en());
        team.setNameAr(bi.ar());
        team.setDescription(req.description());
        if (req.color() != null) team.setColor(req.color());
        if (req.active() != null) team.setActive(req.active());
        teamRepository.save(team);
        long[] counts = loadPerTeamCounts().getOrDefault(id, new long[5]);
        return toSummary(team, counts, 0);
    }

    @Transactional
    public void deleteTeam(Long id) {
        Team team = teamRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));

        String[][] blockers = {
                {"users", "user"},
                {"projects", "project"},
                {"departments", "department"},
                {"subcategories", "subcategory"},
                {"tickets", "ticket"},
                {"custom_fields", "custom field"},
        };
        StringBuilder message = null;
        for (String[] b : blockers) {
            Long count = null;
            try {
                count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + b[0] + " WHERE team_id = ?", Long.class, id);
            } catch (Exception ignored) {
            }
            if (count != null && count > 0) {
                if (message == null) {
                    message = new StringBuilder("Team still holds ");
                } else {
                    message.append(", ");
                }
                message.append(count).append(' ').append(b[1]).append(count == 1 ? "" : "s");
            }
        }
        if (message != null) {
            message.append(". Deactivate the team instead, or move the data to another team first.");
            throw new ResponseStatusException(HttpStatus.CONFLICT, message.toString());
        }

        try {
            int detached = jdbc.update("UPDATE audit_logs SET team_id = NULL WHERE team_id = ?", id);
            if (detached > 0) {
                org.slf4j.LoggerFactory.getLogger(SuperAdminService.class)
                        .info("Detached {} audit_log row(s) from team {} before delete.", detached, id);
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(SuperAdminService.class)
                    .warn("audit_logs detach on team delete skipped: {}", e.getMessage());
        }

        teamRepository.delete(team);
    }


    public List<SuperAdminDtos.SuperAdminRow> listSuperAdmins() {
        return userRepository.findAllByRole(Role.SUPER_ADMIN).stream()
                .map(u -> new SuperAdminDtos.SuperAdminRow(
                        u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(),
                        u.isActive(), u.getCreatedAt()))
                .toList();
    }

    @Transactional
    public SuperAdminDtos.SuperAdminRow createSuperAdmin(SuperAdminDtos.CreateSuperAdminRequest req) {
        if (userRepository.existsByUsername(req.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists.");
        }
        String display = (req.displayName() == null || req.displayName().isBlank())
                ? req.username() : req.displayName();
        TranslationService.Bilingual bi = translator.toBoth(display);
        User saved = userRepository.save(User.builder()
                .username(req.username())
                .passwordHash(passwordEncoder.encode(req.password()))
                .displayName(display)
                .displayNameEn(bi.en())
                .displayNameAr(bi.ar())
                .email(req.email())
                .role(Role.SUPER_ADMIN)
                .team(null)
                .active(true)
                .build());
        return new SuperAdminDtos.SuperAdminRow(
                saved.getId(), saved.getUsername(), saved.getDisplayName(), saved.getEmail(),
                saved.isActive(), saved.getCreatedAt());
    }


    public List<SuperAdminDtos.ProjectBreakdown> projectsBreakdown() {
        List<SuperAdminDtos.ProjectBreakdown> rows = new ArrayList<>();
        Map<Long, SuperAdminDtos.ProjectBreakdown> byProjectId = new HashMap<>();
        Map<Long, List<SuperAdminDtos.PersonRef>> membersByProject = new HashMap<>();
        Map<Long, List<SuperAdminDtos.PersonRef>> adminsByTeam = new HashMap<>();
        Map<Long, Long> ticketsByProject = new HashMap<>();
        Map<Long, Long> weekTicketsByProject = new HashMap<>();

        LocalDate today = LocalDate.now(clock);
        Instant weekStart = today.minusDays(6).atStartOfDay(clock.getZone()).toInstant();

        try {
            jdbc.query(
                    "SELECT team_id, id, username, COALESCE(display_name, username) " +
                            "FROM users WHERE role = 'ADMIN' AND team_id IS NOT NULL",
                    (RowCallbackHandler) rs -> {
                        Long teamId = rs.getLong(1);
                        adminsByTeam
                                .computeIfAbsent(teamId, k -> new ArrayList<>())
                                .add(new SuperAdminDtos.PersonRef(rs.getLong(2), rs.getString(3), rs.getString(4)));
                    });
        } catch (Exception ignored) {}

        try {
            jdbc.query(
                    "SELECT pm.project_id, u.id, u.username, COALESCE(u.display_name, u.username) " +
                            "FROM project_members pm JOIN users u ON u.id = pm.user_id",
                    (RowCallbackHandler) rs -> {
                        Long projectId = rs.getLong(1);
                        membersByProject
                                .computeIfAbsent(projectId, k -> new ArrayList<>())
                                .add(new SuperAdminDtos.PersonRef(rs.getLong(2), rs.getString(3), rs.getString(4)));
                    });
        } catch (Exception ignored) {}

        try {
            jdbc.query(
                    "SELECT project_id, COUNT(*) FROM tickets WHERE project_id IS NOT NULL GROUP BY project_id",
                    (RowCallbackHandler) rs -> ticketsByProject.put(rs.getLong(1), rs.getLong(2)));
            jdbc.query(
                    "SELECT project_id, COUNT(*) FROM tickets WHERE project_id IS NOT NULL AND submitted_at >= ? GROUP BY project_id",
                    ps -> ps.setTimestamp(1, Timestamp.from(weekStart)),
                    (RowCallbackHandler) rs -> weekTicketsByProject.put(rs.getLong(1), rs.getLong(2))
            );
        } catch (Exception ignored) {}

        try {
            jdbc.query(
                    "SELECT p.id, p.name, p.name_en, p.name_ar, p.status, " +
                            "       t.id AS tid, t.name AS tname, t.color AS tcolor " +
                            "FROM projects p LEFT JOIN teams t ON t.id = p.team_id " +
                            "ORDER BY p.created_at DESC",
                    (RowCallbackHandler) rs -> {
                        long rawTid = rs.getLong("tid");
                        Long teamId = rs.wasNull() ? null : rawTid;
                        SuperAdminDtos.ProjectBreakdown row = new SuperAdminDtos.ProjectBreakdown(
                                rs.getLong("id"),
                                rs.getString("name"),
                                rs.getString("name_en"),
                                rs.getString("name_ar"),
                                teamId,
                                rs.getString("tname"),
                                rs.getString("tcolor"),
                                teamId != null
                                        ? adminsByTeam.getOrDefault(teamId, List.of())
                                        : List.of(),
                                List.of(),
                                0L, 0L,
                                rs.getString("status")
                        );
                        rows.add(row);
                        byProjectId.put(row.projectId(), row);
                    });
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(SuperAdminService.class)
                    .warn("projects-breakdown main query failed: {}", e.getMessage());
        }

        List<SuperAdminDtos.ProjectBreakdown> out = new ArrayList<>(rows.size());
        for (SuperAdminDtos.ProjectBreakdown r : rows) {
            List<SuperAdminDtos.PersonRef> members = new ArrayList<>(
                    membersByProject.getOrDefault(r.projectId(), List.of()));
            members.sort(Comparator.comparing(SuperAdminDtos.PersonRef::username, String.CASE_INSENSITIVE_ORDER));
            out.add(new SuperAdminDtos.ProjectBreakdown(
                    r.projectId(), r.projectName(), r.projectNameEn(), r.projectNameAr(),
                    r.teamId(), r.teamName(), r.teamColor(),
                    r.teamAdmins(),
                    members,
                    ticketsByProject.getOrDefault(r.projectId(), 0L),
                    weekTicketsByProject.getOrDefault(r.projectId(), 0L),
                    r.status()
            ));
        }
        return out;
    }


    @Transactional
    public SuperAdminDtos.TeamAdminRow createTeamAdmin(Long teamId, SuperAdminDtos.CreateTeamAdminRequest req) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
        if (!team.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Team is deactivated. Re-activate before creating members inside it.");
        }
        if (userRepository.existsByTeamIdAndRole(teamId, Role.ADMIN)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This team already has an admin. Each admin owns a separate team — "
                            + "create a new team for the new admin instead.");
        }
        if (userRepository.existsByUsername(req.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists.");
        }
        String display = (req.displayName() == null || req.displayName().isBlank())
                ? req.username() : req.displayName();
        TranslationService.Bilingual bi = translator.toBoth(display);
        User saved = userRepository.save(User.builder()
                .username(req.username())
                .passwordHash(passwordEncoder.encode(req.password()))
                .displayName(display)
                .displayNameEn(bi.en())
                .displayNameAr(bi.ar())
                .email(req.email())
                .role(Role.ADMIN)
                .team(team)
                .active(true)
                .build());
        return new SuperAdminDtos.TeamAdminRow(
                saved.getId(), saved.getUsername(), saved.getDisplayName(), saved.getEmail(),
                saved.getRole().name(), saved.isActive(), saved.getCreatedAt());
    }

    @Transactional
    public SuperAdminDtos.AdminWithTeamResponse createAdminWithNewTeam(
            SuperAdminDtos.CreateAdminWithTeamRequest req) {
        if (userRepository.existsByUsername(req.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists.");
        }
        String desiredSlug = (req.teamSlug() != null && !req.teamSlug().isBlank())
                ? req.teamSlug().toLowerCase()
                : deriveSlug(req.username());
        String slug = ensureUniqueSlug(desiredSlug);
        String teamName = (req.teamName() != null && !req.teamName().isBlank())
                ? req.teamName()
                : defaultWorkspaceName(req.displayName(), req.username());
        TranslationService.Bilingual teamBi = translator.toBoth(teamName);
        Team team = teamRepository.save(Team.builder()
                .slug(slug)
                .name(teamName)
                .nameEn(teamBi.en())
                .nameAr(teamBi.ar())
                .description(req.teamDescription())
                .color(req.teamColor() != null ? req.teamColor() : "#6366f1")
                .createdById(TenantContext.getUserId())
                .active(true)
                .build());

        String display = (req.displayName() == null || req.displayName().isBlank())
                ? req.username() : req.displayName();
        TranslationService.Bilingual userBi = translator.toBoth(display);
        User admin = userRepository.save(User.builder()
                .username(req.username())
                .passwordHash(passwordEncoder.encode(req.password()))
                .displayName(display)
                .displayNameEn(userBi.en())
                .displayNameAr(userBi.ar())
                .email(req.email())
                .role(Role.ADMIN)
                .team(team)
                .active(true)
                .build());

        return new SuperAdminDtos.AdminWithTeamResponse(
                toSummary(team, new long[5], 0),
                new SuperAdminDtos.TeamAdminRow(
                        admin.getId(), admin.getUsername(), admin.getDisplayName(), admin.getEmail(),
                        admin.getRole().name(), admin.isActive(), admin.getCreatedAt())
        );
    }

    private String deriveSlug(String username) {
        String base = username == null ? "team" : username.trim().toLowerCase();
        String cleaned = base.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return cleaned.isEmpty() ? "team" : cleaned;
    }

    private String ensureUniqueSlug(String desired) {
        String candidate = desired;
        int i = 2;
        while (teamRepository.existsBySlugIgnoreCase(candidate)) {
            candidate = desired + "-" + i++;
            if (i > 500) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Could not derive a free team slug from '" + desired + "'.");
            }
        }
        return candidate;
    }

    private String defaultWorkspaceName(String displayName, String username) {
        String base = (displayName != null && !displayName.isBlank()) ? displayName : username;
        return base + "'s workspace";
    }

    public List<SuperAdminDtos.TeamAdminRow> listTeamMembers(Long teamId) {
        teamRepository.findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
        return userRepository.findAllByTeamIdOrderByCreatedAtDesc(teamId).stream()
                .map(u -> new SuperAdminDtos.TeamAdminRow(
                        u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(),
                        u.getRole().name(), u.isActive(), u.getCreatedAt()))
                .toList();
    }


    public SuperAdminDtos.EnterTeamResponse enterTeam(Long teamId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
        if (!team.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Team is deactivated. Re-activate before entering.");
        }
        return new SuperAdminDtos.EnterTeamResponse(
                team.getId(), team.getSlug(), team.getName(),
                "X-Impersonate-Team-Id"
        );
    }
}
