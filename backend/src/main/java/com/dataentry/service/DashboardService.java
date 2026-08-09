package com.dataentry.service;

import com.dataentry.dto.DashboardDtos;
import com.dataentry.model.Department;
import com.dataentry.model.Subcategory;
import com.dataentry.model.TicketStatus;
import com.dataentry.model.User;
import com.dataentry.repository.CustomFieldRepository;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.SubcategoryRepository;
import com.dataentry.repository.TicketRepository;
import com.dataentry.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final Clock clock;
    private final TicketRepository ticketRepository;
    private final DepartmentRepository departmentRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final CustomFieldRepository customFieldRepository;
    private final UserRepository userRepository;

    public DashboardService(Clock clock,
                            TicketRepository ticketRepository,
                            DepartmentRepository departmentRepository,
                            SubcategoryRepository subcategoryRepository,
                            CustomFieldRepository customFieldRepository,
                            UserRepository userRepository) {
        this.clock = clock;
        this.ticketRepository = ticketRepository;
        this.departmentRepository = departmentRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.customFieldRepository = customFieldRepository;
        this.userRepository = userRepository;
    }

    // ---------- admin stats (moved from TicketService) ----------

    public DashboardDtos.AdminStats adminStats() {
        LocalDate today = today();
        Instant startOfToday = today.atStartOfDay(zone()).toInstant();

        return new DashboardDtos.AdminStats(
                ticketRepository.count(),
                departmentRepository.count(),
                customFieldRepository.countByActiveTrue(),
                userRepository.count(),
                ticketRepository.countByStatus(TicketStatus.IN_PROGRESS),
                ticketRepository.countByStatus(TicketStatus.REVIEW),
                ticketRepository.countByStatus(TicketStatus.COMPLETED),
                ticketRepository.countByStatusAndSubmittedAtGreaterThanEqual(TicketStatus.COMPLETED, startOfToday)
        );
    }

    // ---------- weekly report (moved from TicketService) ----------

    public DashboardDtos.ReportData report() {
        LocalDate today = today();
        LocalDate sixDaysAgo = today.minusDays(6);
        Instant weekStart = sixDaysAgo.atStartOfDay(zone()).toInstant();

        // Bucketed byDay counts — one query returning timestamps, grouped in Java
        Map<String, Long> byDay = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            byDay.put(sixDaysAgo.plusDays(i).toString(), 0L);
        }
        for (Instant t : ticketRepository.submissionTimesSince(weekStart)) {
            String key = t.atZone(zone()).toLocalDate().toString();
            byDay.computeIfPresent(key, (k, v) -> v + 1);
        }

        // Top performers (COMPLETED, top 5) — one JPQL query with LIMIT
        List<DashboardDtos.TopPerformer> topPerformers =
                ticketRepository.topPerformersByStatus(TicketStatus.COMPLETED, PageRequest.of(0, 5));

        long completedThisWeek = ticketRepository
                .countByStatusAndSubmittedAtGreaterThanEqual(TicketStatus.COMPLETED, weekStart);

        return new DashboardDtos.ReportData(byDay, topPerformers, completedThisWeek);
    }

    // ---------- domains ----------

    public List<DashboardDtos.DomainStats> domains() {
        List<Department> depts = departmentRepository.findAll();
        Map<Long, Long> totals = toLongMap(ticketRepository.countByDepartment());
        Map<Long, Long> agents = toLongMap(ticketRepository.distinctAgentsByDepartment());
        Map<Long, Map<String, Long>> byStatus = toDepartmentStatusMap(ticketRepository.countByDepartmentAndStatus());
        Map<Long, Long> subCounts = countSubcategoriesByDepartment(depts);

        LocalDate today = today();
        LocalDate weekStart = today.minusDays(6);
        Instant since = weekStart.atStartOfDay(zone()).toInstant();
        Map<Long, List<DashboardDtos.DailyCount>> sparklines = buildDailyBucketsFromDepartments(
                ticketRepository.departmentSubmissionsSince(since), weekStart, 7);

        return depts.stream()
                .sorted(Comparator.comparing(Department::getName, String.CASE_INSENSITIVE_ORDER))
                .map(d -> new DashboardDtos.DomainStats(
                        d.getId(),
                        d.getName(),
                        totals.getOrDefault(d.getId(), 0L),
                        subCounts.getOrDefault(d.getId(), 0L),
                        agents.getOrDefault(d.getId(), 0L),
                        byStatus.getOrDefault(d.getId(), emptyStatusMap()),
                        sparklines.getOrDefault(d.getId(), emptyDaily(weekStart, 7))
                ))
                .toList();
    }

    public DashboardDtos.DomainDetail domain(Long departmentId) {
        Department dept = departmentRepository.findById(departmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found"));

        Map<Long, Long> totals = toLongMap(ticketRepository.countByDepartment());
        Map<Long, Long> agents = toLongMap(ticketRepository.distinctAgentsByDepartment());
        Map<Long, Map<String, Long>> byStatus = toDepartmentStatusMap(ticketRepository.countByDepartmentAndStatus());

        LocalDate today = today();
        LocalDate windowStart = today.minusDays(29);
        Instant since = windowStart.atStartOfDay(zone()).toInstant();
        Map<Long, List<DashboardDtos.DailyCount>> sparklines = buildDailyBucketsFromDepartments(
                ticketRepository.departmentSubmissionsSince(since), windowStart, 30);

        return new DashboardDtos.DomainDetail(
                dept.getId(),
                dept.getName(),
                totals.getOrDefault(dept.getId(), 0L),
                agents.getOrDefault(dept.getId(), 0L),
                byStatus.getOrDefault(dept.getId(), emptyStatusMap()),
                sparklines.getOrDefault(dept.getId(), emptyDaily(windowStart, 30)),
                subcategoriesFor(dept)
        );
    }

    // ---------- subcategories ----------

    public List<DashboardDtos.SubcategoryStats> subcategories(Long departmentId) {
        if (departmentId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "departmentId is required");
        }
        Department dept = departmentRepository.findById(departmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found"));
        return subcategoriesFor(dept);
    }

    private List<DashboardDtos.SubcategoryStats> subcategoriesFor(Department dept) {
        List<Subcategory> subs = subcategoryRepository.findAllByDepartmentIdOrderByNameAsc(dept.getId());
        Map<Long, Long> totals = subcategoryCountMap(ticketRepository.countBySubcategoryForDepartment(dept.getId()));
        Map<Long, Map<String, Long>> byStatus =
                toSubcategoryStatusMap(ticketRepository.countBySubcategoryAndStatusForDepartment(dept.getId()));

        LocalDate today = today();
        LocalDate weekStart = today.minusDays(6);
        Instant since = weekStart.atStartOfDay(zone()).toInstant();
        Map<Long, List<DashboardDtos.DailyCount>> sparklines = buildDailyBucketsFromSubcategories(
                ticketRepository.subcategorySubmissionsSinceForDepartment(dept.getId(), since),
                weekStart, 7);

        return subs.stream()
                .map(s -> new DashboardDtos.SubcategoryStats(
                        s.getId(),
                        s.getName(),
                        dept.getId(),
                        dept.getName(),
                        totals.getOrDefault(s.getId(), 0L),
                        byStatus.getOrDefault(s.getId(), emptyStatusMap()),
                        sparklines.getOrDefault(s.getId(), emptyDaily(weekStart, 7))
                ))
                .toList();
    }

    // ---------- users ----------

    public DashboardDtos.LeaderboardResponse leaderboard(String range) {
        String normalized = normalizeRange(range);
        LocalDate today = today();
        LocalDate windowStart = switch (normalized) {
            case "day" -> today;
            case "month" -> today.minusDays(29);
            default -> today.minusDays(6); // "week"
        };
        int windowDays = (int) java.time.temporal.ChronoUnit.DAYS.between(windowStart, today) + 1;
        Instant since = windowStart.atStartOfDay(zone()).toInstant();

        List<DashboardDtos.LeaderboardRowRaw> board = ticketRepository.leaderboardSince(since);
        long distinctAgents = ticketRepository.distinctAgentsSince(since);

        Instant todayStart = today.atStartOfDay(zone()).toInstant();
        Map<Long, Long> todayByUser = userCountMap(ticketRepository.countByUserSince(todayStart));
        Instant weekStart = today.minusDays(6).atStartOfDay(zone()).toInstant();
        Map<Long, Long> weekByUser = userCountMap(ticketRepository.countByUserSince(weekStart));

        List<DashboardDtos.AgentLeaderboardRow> rows = board.stream()
                .map(row -> {
                    double avg = windowDays > 0 ? (double) row.total() / windowDays : 0;
                    return new DashboardDtos.AgentLeaderboardRow(
                            row.userId(),
                            row.username(),
                            row.displayName() != null ? row.displayName() : row.username(),
                            row.total(),
                            todayByUser.getOrDefault(row.userId(), 0L),
                            weekByUser.getOrDefault(row.userId(), 0L),
                            Math.round(avg * 100.0) / 100.0
                    );
                })
                .toList();

        return new DashboardDtos.LeaderboardResponse(normalized, distinctAgents, rows);
    }

    public DashboardDtos.UserActivity userActivity(Long userId, Integer days) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        int window = (days == null || days <= 0 || days > 365) ? 30 : days;
        LocalDate today = today();
        LocalDate start = today.minusDays(window - 1L);
        Instant since = start.atStartOfDay(zone()).toInstant();

        List<Instant> times = ticketRepository.userSubmissionTimesSince(userId, since);
        Map<LocalDate, Long> daily = times.stream()
                .collect(Collectors.groupingBy(t -> t.atZone(zone()).toLocalDate(), Collectors.counting()));

        List<DashboardDtos.DailyCount> series = new ArrayList<>(window);
        for (int i = 0; i < window; i++) {
            LocalDate d = start.plusDays(i);
            series.add(new DashboardDtos.DailyCount(d, daily.getOrDefault(d, 0L)));
        }

        long total = times.size();

        List<DashboardDtos.UserBreakdownRow> byDept = ticketRepository.userTicketsByDepartment(userId).stream()
                .map(r -> new DashboardDtos.UserBreakdownRow(r.groupId(), r.groupName(), r.total()))
                .sorted(Comparator.comparingLong(DashboardDtos.UserBreakdownRow::count).reversed())
                .toList();

        List<DashboardDtos.UserBreakdownRow> bySub = ticketRepository.userTicketsBySubcategory(userId).stream()
                .map(r -> new DashboardDtos.UserBreakdownRow(r.groupId(), r.groupName(), r.total()))
                .sorted(Comparator.comparingLong(DashboardDtos.UserBreakdownRow::count).reversed())
                .toList();

        Map<String, Long> byStatus = emptyStatusMap();
        for (DashboardDtos.StatusCount sc : ticketRepository.userTicketsByStatus(userId)) {
            byStatus.put(sc.status().name(), sc.total());
        }

        return new DashboardDtos.UserActivity(
                user.getId(),
                user.getUsername(),
                user.getDisplayName() != null ? user.getDisplayName() : user.getUsername(),
                total,
                window,
                series,
                byDept,
                bySub,
                byStatus
        );
    }

    // ---------- helpers ----------

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private ZoneId zone() {
        return clock.getZone();
    }

    private String normalizeRange(String range) {
        if (range == null) return "week";
        String r = range.toLowerCase(Locale.ROOT);
        return switch (r) {
            case "day", "week", "month" -> r;
            default -> "week";
        };
    }

    private Map<Long, Long> countSubcategoriesByDepartment(List<Department> depts) {
        Map<Long, Long> out = new HashMap<>();
        for (Department d : depts) {
            out.put(d.getId(), subcategoryRepository.countByDepartmentId(d.getId()));
        }
        return out;
    }

    private Map<Long, Long> toLongMap(List<DashboardDtos.DepartmentCount> rows) {
        Map<Long, Long> out = new HashMap<>();
        for (DashboardDtos.DepartmentCount r : rows) {
            if (r.departmentId() != null) out.put(r.departmentId(), r.total());
        }
        return out;
    }

    private Map<Long, Long> subcategoryCountMap(List<DashboardDtos.SubcategoryCount> rows) {
        Map<Long, Long> out = new HashMap<>();
        for (DashboardDtos.SubcategoryCount r : rows) {
            if (r.subcategoryId() != null) out.put(r.subcategoryId(), r.total());
        }
        return out;
    }

    private Map<Long, Long> userCountMap(List<DashboardDtos.UserCount> rows) {
        Map<Long, Long> out = new HashMap<>();
        for (DashboardDtos.UserCount r : rows) {
            if (r.userId() != null) out.put(r.userId(), r.total());
        }
        return out;
    }

    private Map<Long, Map<String, Long>> toDepartmentStatusMap(List<DashboardDtos.DepartmentStatusCount> rows) {
        Map<Long, Map<String, Long>> out = new HashMap<>();
        for (DashboardDtos.DepartmentStatusCount r : rows) {
            if (r.departmentId() == null) continue;
            out.computeIfAbsent(r.departmentId(), k -> emptyStatusMap()).put(r.status().name(), r.total());
        }
        return out;
    }

    private Map<Long, Map<String, Long>> toSubcategoryStatusMap(List<DashboardDtos.SubcategoryStatusCount> rows) {
        Map<Long, Map<String, Long>> out = new HashMap<>();
        for (DashboardDtos.SubcategoryStatusCount r : rows) {
            if (r.subcategoryId() == null) continue;
            out.computeIfAbsent(r.subcategoryId(), k -> emptyStatusMap()).put(r.status().name(), r.total());
        }
        return out;
    }

    private Map<String, Long> emptyStatusMap() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("IN_PROGRESS", 0L);
        m.put("REVIEW", 0L);
        m.put("COMPLETED", 0L);
        return m;
    }

    private List<DashboardDtos.DailyCount> emptyDaily(LocalDate start, int days) {
        List<DashboardDtos.DailyCount> out = new ArrayList<>(days);
        for (int i = 0; i < days; i++) out.add(new DashboardDtos.DailyCount(start.plusDays(i), 0L));
        return out;
    }

    private Map<Long, List<DashboardDtos.DailyCount>> buildDailyBucketsFromDepartments(
            List<DashboardDtos.DepartmentSubmission> rows, LocalDate start, int days) {
        Map<Long, Map<LocalDate, Long>> grouped = new HashMap<>();
        for (DashboardDtos.DepartmentSubmission r : rows) {
            if (r.departmentId() == null || r.submittedAt() == null) continue;
            LocalDate d = r.submittedAt().atZone(zone()).toLocalDate();
            grouped.computeIfAbsent(r.departmentId(), k -> new HashMap<>()).merge(d, 1L, Long::sum);
        }
        return fillDailySeries(grouped, start, days);
    }

    private Map<Long, List<DashboardDtos.DailyCount>> buildDailyBucketsFromSubcategories(
            List<DashboardDtos.SubcategorySubmission> rows, LocalDate start, int days) {
        Map<Long, Map<LocalDate, Long>> grouped = new HashMap<>();
        for (DashboardDtos.SubcategorySubmission r : rows) {
            if (r.subcategoryId() == null || r.submittedAt() == null) continue;
            LocalDate d = r.submittedAt().atZone(zone()).toLocalDate();
            grouped.computeIfAbsent(r.subcategoryId(), k -> new HashMap<>()).merge(d, 1L, Long::sum);
        }
        return fillDailySeries(grouped, start, days);
    }

    private Map<Long, List<DashboardDtos.DailyCount>> fillDailySeries(
            Map<Long, Map<LocalDate, Long>> grouped, LocalDate start, int days) {
        Map<Long, List<DashboardDtos.DailyCount>> out = new HashMap<>();
        for (var e : grouped.entrySet()) {
            List<DashboardDtos.DailyCount> series = new ArrayList<>(days);
            for (int i = 0; i < days; i++) {
                LocalDate d = start.plusDays(i);
                series.add(new DashboardDtos.DailyCount(d, e.getValue().getOrDefault(d, 0L)));
            }
            out.put(e.getKey(), series);
        }
        return out;
    }
}
