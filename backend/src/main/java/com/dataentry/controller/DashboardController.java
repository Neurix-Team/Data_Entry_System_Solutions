package com.dataentry.controller;

import com.dataentry.dto.DashboardDtos;
import com.dataentry.service.DashboardService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/domains")
    public List<DashboardDtos.DomainStats> domains() {
        return service.domains();
    }

    @GetMapping("/domains/{id}")
    public DashboardDtos.DomainDetail domain(@PathVariable Long id) {
        return service.domain(id);
    }

    @GetMapping("/subcategories")
    public List<DashboardDtos.SubcategoryStats> subcategories(@RequestParam Long departmentId) {
        return service.subcategories(departmentId);
    }

    @GetMapping("/users")
    public DashboardDtos.LeaderboardResponse users(
            @RequestParam(defaultValue = "week") String range) {
        return service.leaderboard(range);
    }

    @GetMapping("/users/{id}")
    public DashboardDtos.UserActivity user(
            @PathVariable Long id,
            @RequestParam(defaultValue = "30") Integer days) {
        return service.userActivity(id, days);
    }
}
