package com.dataentry.service;

import com.dataentry.dto.GoalDtos;
import com.dataentry.model.User;
import com.dataentry.repository.TicketRepository;
import com.dataentry.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Personal goals + streaks (B5). Everything derives from the agent's own submission
 * timestamps — no new tables, and a soft-deleted entry still counts on the day it
 * was made (the work happened). The goal itself is informational, never enforced.
 */
@Service
public class GoalService {

    private static final int LOOKBACK_DAYS = 90;
    private static final int MIN_GOAL = 1;
    private static final int MAX_GOAL = 1000;

    private final TicketRepository tickets;
    private final UserRepository users;
    private final Clock clock;

    public GoalService(TicketRepository tickets, UserRepository users, Clock clock) {
        this.tickets = tickets;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GoalDtos.GoalsResponse get(User current) {
        if (current == null || current.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock);

        // One query: the 90-day look-back covers the 7-day chart and both streaks.
        List<Instant> times = tickets.userSubmissionTimesSince(
                current.getId(), today.minusDays(LOOKBACK_DAYS - 1)
                        .atStartOfDay(ZoneId.systemDefault()).toInstant());

        Set<LocalDate> activeDays = new HashSet<>();
        for (Instant t : times) {
            activeDays.add(LocalDate.ofInstant(t, ZoneId.systemDefault()));
        }

        Instant startOfToday = today.atStartOfDay(ZoneId.systemDefault()).toInstant();
        long todayCount = times.stream().filter(t -> !t.isBefore(startOfToday)).count();

        LocalDate weekStart = today.minusDays(6);
        long weekCount = times.stream()
                .filter(t -> !t.isBefore(weekStart.atStartOfDay(ZoneId.systemDefault()).toInstant()))
                .count();

        List<GoalDtos.DayCount> last7Days = new ArrayList<>(7);
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            last7Days.add(new GoalDtos.DayCount(day.toString(), activeDays.contains(day) ? countOn(times, day) : 0));
        }

        return new GoalDtos.GoalsResponse(
                current.getDailyGoal(),
                todayCount,
                weekCount,
                currentStreak(activeDays, today),
                bestStreak(activeDays, today),
                last7Days
        );
    }

    @Transactional
    public GoalDtos.GoalsResponse update(User current, Integer dailyGoal) {
        if (dailyGoal == null || dailyGoal < MIN_GOAL || dailyGoal > MAX_GOAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Daily goal must be between " + MIN_GOAL + " and " + MAX_GOAL);
        }
        User managed = users.findById(current.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        managed.setDailyGoal(dailyGoal);
        users.save(managed);
        return get(managed);
    }

    private long countOn(List<Instant> times, LocalDate day) {
        Instant from = day.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        return times.stream().filter(t -> !t.isBefore(from) && t.isBefore(to)).count();
    }

    /** Streak survives "today so far": counts back from today, or yesterday if today is still empty. */
    private int currentStreak(Set<LocalDate> activeDays, LocalDate today) {
        LocalDate cursor = activeDays.contains(today) ? today : today.minusDays(1);
        int streak = 0;
        while (activeDays.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    private int bestStreak(Set<LocalDate> activeDays, LocalDate today) {
        int best = 0;
        int run = 0;
        LocalDate cursor = today.minusDays(LOOKBACK_DAYS - 1);
        for (int i = 0; i < LOOKBACK_DAYS; i++) {
            if (activeDays.contains(cursor)) {
                run++;
                best = Math.max(best, run);
            } else {
                run = 0;
            }
            cursor = cursor.plusDays(1);
        }
        return best;
    }
}