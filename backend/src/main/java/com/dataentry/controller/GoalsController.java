package com.dataentry.controller;

import com.dataentry.dto.GoalDtos;
import com.dataentry.model.User;
import com.dataentry.service.GoalService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user/goals")
public class GoalsController {

    private final GoalService service;

    public GoalsController(GoalService service) {
        this.service = service;
    }

    @GetMapping
    public GoalDtos.GoalsResponse get(@AuthenticationPrincipal User current) {
        return service.get(current);
    }

    @PatchMapping
    public GoalDtos.GoalsResponse update(@Valid @RequestBody GoalDtos.UpdateGoalRequest req,
                                         @AuthenticationPrincipal User current) {
        return service.update(current, req.dailyGoal());
    }
}