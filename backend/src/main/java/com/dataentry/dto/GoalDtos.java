package com.dataentry.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Personal goals + streaks (B5) — the agent dashboard's motivation card. */
public class GoalDtos {

    public record DayCount(String day, long count) {}

    public record GoalsResponse(
            int dailyGoal,
            long todayCount,
            long weekCount,
            /** Consecutive entry-days ending today (or yesterday, until today's first entry). */
            int currentStreak,
            /** Best run within the look-back window. */
            int bestStreak,
            List<DayCount> last7Days
    ) {}

    public record UpdateGoalRequest(@NotNull Integer dailyGoal) {}
}