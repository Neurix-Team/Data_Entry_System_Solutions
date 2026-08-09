package com.dataentry.service;

import com.dataentry.dto.DashboardDtos;
import com.dataentry.model.Department;
import com.dataentry.model.Subcategory;
import com.dataentry.model.User;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.SubcategoryRepository;
import com.dataentry.repository.TicketRepository;
import com.dataentry.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final TicketRepository ticketRepository;
    private final DepartmentRepository departmentRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final UserRepository userRepository;

    public DashboardService(TicketRepository ticketRepository,
                            DepartmentRepository departmentRepository,
                            SubcategoryRepository subcategoryRepository,
                            UserRepository userRepository) {
        this.ticketRepository = ticketRepository;
        this.departmentRepository = departmentRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.userRepository = userRepository;
    }

    // ---------- domains ----------

    public List<DashboardDtos.DomainStats> domains() {
        List<Department> depts = departmentRepository.findAll();
        Map<Long, Long> totals = toLongMap(ticketRepository.countByDepartment());
        Map<Long, Long> agents = toLongMap(ticketRepository.distinctAgentsByDepartment());
        Map<Long, Map<String, Long>> byStatus = toStatusMap(ticketRepository.countByDepartmentAndStatus());
        Map<Long, Long> subCounts = countSubcategoriesByDepartment(depts);

        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.minusDays(6);
        Instant since = weekStart.atStartOfDay(ZONE).toInstant();
        Map<Long, List<DashboardDtos.DailyCount>> sparklines =
                buildDailyBucketsFrom(ticketRepository.departmentSubmissionsSince(since), weekStart, 7);

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
        Map<Long, Map<String, Long>> byStatus = toStatusMap(ticketRepository.countByDepartmentAndStatus());

        LocalDate today = LocalDate.now();
        LocalDate windowStart = today.minusDays(29);
        Instant since = windowStart.atStartOfDay(ZONE).toInstant();
        Map<Long, List<DashboardDtos.DailyCount>> sparklines =
                buildDailyBucketsFrom(ticketRepository.departmentSubmissionsSince(since), windowStart, 30);

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
        Map<Long, Long> totals = toLongMap(ticketRepository.countBySubcategoryForDepartment(dept.getId()));
        Map<Long, Map<String, Long>> byStatus =
                toStatusMap(ticketRepository.countBySubcategoryAndStatusForDepartment(dept.getId()));

        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.minusDays(6);
        Instant since = weekStart.atStartOfDay(ZONE).toInstant();
        Map<Long, List<DashboardDtos.DailyCount>> sparklines =
                buildDailyBucketsFrom(
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
        LocalDate today = LocalDate.now();
        LocalDate windowStart = switch (normalized) {
            case "day" -> today;
            case "week" -> today.minusDays(6);
            case "month" -> today.minusDays(29);
            default -> today.minusDays(6);
        };
        int windowDays = (int) java.time.temporal.ChronoUnit.DAYS.between(windowStart, today) + 1;
        Instant since = windowStart.atStartOfDay(ZONE).toInstant();

        List<Object[]> board = ticketRepository.leaderboardSince(since);
        long distinctAgents = ticketRepository.distinctAgentsSince(since);

        // Cross-check totals and today's count using in-range queries + today bucket
        Instant todayStart = today.atStartOfDay(ZONE).toInstant();
        Map<Long, Long> todayByUser = toLongMap(ticketRepository.countByUserSince(todayStart));
        Instant weekStart = today.minusDays(6).atStartOfDay(ZONE).toInstant();
        Map<Long, Long> weekByUser = toLongMap(ticketRepository.countByUserSince(weekStart));

        // "Total" here means tickets in the selected range
        List<DashboardDtos.AgentLeaderboardRow> rows = board.stream()
                .map(row -> {
                    Long uid = ((Number) row[0]).longValue();
                    String displayName = (String) row[1];
                    String username = (String) row[2];
                    long totalInRange = ((Number) row[3]).longValue();
                    double avg = windowDays > 0 ? (double) totalInRange / windowDays : 0;
                    return new DashboardDtos.AgentLeaderboardRow(
                            uid,
                            username,
                            displayName != null ? displayName : username,
                            totalInRange,
                            todayByUser.getOrDefault(uid, 0L),
                            weekByUser.getOrDefault(uid, 0L),
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
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(window - 1L);
        Instant since = start.atStartOfDay(ZONE).toInstant();

        List<Instant> times = ticketRepository.userSubmissionTimesSince(userId, since);
        Map<LocalDate, Long> daily = times.stream()
                .collect(Collectors.groupingBy(t -> t.atZone(ZONE).toLocalDate(), Collectors.counting()));

        List<DashboardDtos.DailyCount> series = new ArrayList<>(window);
        for (int i = 0; i < window; i++) {
            LocalDate d = start.plusDays(i);
            series.add(new DashboardDtos.DailyCount(d, daily.getOrDefault(d, 0L)));
        }

        long total = times.size();

        List<DashboardDtos.UserBreakdownRow> byDept = ticketRepository.userTicketsByDepartment(userId).stream()
                .map(r -> new DashboardDtos.UserBreakdownRow(
                        ((Number) r[0]).longValue(),
                        (String) r[1],
                        ((Number) r[2]).longValue()))
                .sorted(Comparator.comparingLong(DashboardDtos.UserBreakdownRow::count).reversed())
                .toList();

        List<DashboardDtos.UserBreakdownRow> bySub = ticketRepository.userTicketsBySubcategory(userId).stream()
                .map(r -> new DashboardDtos.UserBreakdownRow(
                        ((Number) r[0]).longValue(),
                        (String) r[1],
                        ((Number) r[2]).longValue()))
                .sorted(Comparator.comparingLong(DashboardDtos.UserBreakdownRow::count).reversed())
                .toList();

        Map<String, Long> byStatus = new LinkedHashMap<>();
        byStatus.put("IN_PROGRESS", 0L);
        byStatus.put("REVIEW", 0L);
        byStatus.put("COMPLETED", 0L);
        for (Object[] r : ticketRepository.userTicketsByStatus(userId)) {
            byStatus.put(r[0].toString(), ((Number) r[1]).longValue());
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

    private Map<Long, Long> toLongMap(List<Object[]> rows) {
        Map<Long, Long> out = new HashMap<>();
        for (Object[] r : rows) {
            if (r[0] == null) continue;
            out.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        }
        return out;
    }

    private Map<Long, Map<String, Long>> toStatusMap(List<Object[]> rows) {
        Map<Long, Map<String, Long>> out = new HashMap<>();
        for (Object[] r : rows) {
            if (r[0] == null) continue;
            Long groupId = ((Number) r[0]).longValue();
            String status = r[1].toString();
            long count = ((Number) r[2]).longValue();
            out.computeIfAbsent(groupId, k -> emptyStatusMap()).put(status, count);
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

    private Map<Long, List<DashboardDtos.DailyCount>> buildDailyBucketsFrom(
            List<Object[]> tuples, LocalDate start, int days) {
        Map<Long, Map<LocalDate, Long>> grouped = new HashMap<>();
        for (Object[] row : tuples) {
            if (row[0] == null || row[1] == null) continue;
            Long groupId = ((Number) row[0]).longValue();
            Instant when = (Instant) row[1];
            LocalDate d = when.atZone(ZONE).toLocalDate();
            grouped.computeIfAbsent(groupId, k -> new HashMap<>()).merge(d, 1L, Long::sum);
        }
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
