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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Backing service for {@code /api/super/*}. Runs exclusively under SUPER_ADMIN auth, so the
 * Hibernate tenant filter is never enabled here — every query sees every team.
 *
 * <p>Aggregation is done in bulk via {@link JdbcTemplate} rather than looping repository
 * calls per team, so the overview page loads in a single round-trip regardless of team count.
 */
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

    // ---------- overview ----------

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
                Long.class, startOfToday.toString());

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
        // Index: 0=users, 1=admins, 2=projects, 3=departments, 4=tickets
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
            // Table may not exist yet on the very first boot before Hibernate creates it.
        }
    }

    private Map<Long, Long> weeklyTicketCounts(Instant since) {
        Map<Long, Long> out = new HashMap<>();
        try {
            jdbc.query(
                    "SELECT team_id, COUNT(*) FROM tickets " +
                            "WHERE team_id IS NOT NULL AND submitted_at >= ? GROUP BY team_id",
                    ps -> ps.setString(1, since.toString()),
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

    // ---------- team CRUD ----------

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
        // Re-load counts so the response accurately reflects the team's current size.
        long[] counts = loadPerTeamCounts().getOrDefault(id, new long[5]);
        return toSummary(team, counts, 0);
    }

    @Transactional
    public void deleteTeam(Long id) {
        Team team = teamRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
        long users = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE team_id = ?", Long.class, id);
        if (users > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Team has " + users + " user(s). Deactivate instead, or move users to another team first.");
        }
        teamRepository.delete(team);
    }

    // ---------- super-admin management ----------

    public List<SuperAdminDtos.SuperAdminRow> listSuperAdmins() {
        return userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.SUPER_ADMIN)
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

    // ---------- impersonation ----------

    /**
     * Confirms the target team exists + is active and returns a small envelope the frontend
     * uses to remember which team to send in the impersonation header. No JWT is minted —
     * the existing SUPER_ADMIN cookie plus the {@code X-Impersonate-Team-Id} header are
     * what {@link com.dataentry.security.JwtAuthFilter} looks for.
     */
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
