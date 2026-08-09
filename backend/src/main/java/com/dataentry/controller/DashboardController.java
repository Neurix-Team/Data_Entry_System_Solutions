package com.dataentry.controller;

import com.dataentry.dto.DashboardDtos;
import com.dataentry.model.User;
import com.dataentry.service.DashboardService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    // ---------- admin endpoints (mounted at /api/admin/dashboard) ----------

    @GetMapping("/api/admin/dashboard/domains")
    public List<DashboardDtos.DomainStats> domains() {
        return service.domains();
    }

    @GetMapping("/api/admin/dashboard/domains/{id}")
    public DashboardDtos.DomainDetail domain(@PathVariable Long id) {
        return service.domain(id);
    }

    @GetMapping("/api/admin/dashboard/subcategories")
    public List<DashboardDtos.SubcategoryStats> subcategories(@RequestParam Long departmentId) {
        return service.subcategories(departmentId);
    }

    @GetMapping("/api/admin/dashboard/users")
    public DashboardDtos.LeaderboardResponse users(
            @RequestParam(defaultValue = "week") String range) {
        return service.leaderboard(range);
    }

    @GetMapping("/api/admin/dashboard/users/{id}")
    public DashboardDtos.UserActivity user(
            @PathVariable Long id,
            @RequestParam(defaultValue = "30") Integer days) {
        return service.userActivity(id, days);
    }

    // ---------- user self-dashboard ----------

    @GetMapping("/api/user/dashboard/me")
    public DashboardDtos.MyDashboard me(
            @AuthenticationPrincipal User current,
            @RequestParam(required = false) Integer days) {
        return service.myDashboard(current, days);
    }
}
