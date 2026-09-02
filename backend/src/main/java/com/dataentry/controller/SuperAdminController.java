package com.dataentry.controller;

import com.dataentry.dto.SuperAdminDtos;
import com.dataentry.service.SuperAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Cross-team surface used only by SUPER_ADMIN. URL protection is enforced in
 * {@code SecurityConfig} ({@code /api/super/** → hasRole("SUPER_ADMIN")}).
 *
 * <p>Every endpoint here bypasses the tenant filter — that's the point. Individual team
 * management still goes through {@code /api/admin/**} using the
 * {@code X-Impersonate-Team-Id} header for scoping.
 */
@RestController
@RequestMapping("/api/super")
public class SuperAdminController {

    private final SuperAdminService service;

    public SuperAdminController(SuperAdminService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public SuperAdminDtos.OverviewStats overview() {
        return service.overview();
    }

    @GetMapping("/teams")
    public List<SuperAdminDtos.TeamSummary> listTeams() {
        return service.listTeams();
    }

    @PostMapping("/teams")
    public ResponseEntity<SuperAdminDtos.TeamSummary> createTeam(
            @Valid @RequestBody SuperAdminDtos.CreateTeamRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createTeam(req));
    }

    @PutMapping("/teams/{id}")
    public SuperAdminDtos.TeamSummary updateTeam(
            @PathVariable Long id,
            @Valid @RequestBody SuperAdminDtos.UpdateTeamRequest req) {
        return service.updateTeam(id, req);
    }

    @DeleteMapping("/teams/{id}")
    public ResponseEntity<Void> deleteTeam(@PathVariable Long id) {
        service.deleteTeam(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Confirms the target team is enterable and returns the header the frontend must send.
     * The client then sets {@code X-Impersonate-Team-Id} on subsequent requests. Exiting
     * impersonation is a client-side concern (just stop sending the header).
     */
    @PostMapping("/teams/{id}/enter")
    public SuperAdminDtos.EnterTeamResponse enterTeam(@PathVariable Long id) {
        return service.enterTeam(id);
    }

    @GetMapping("/teams/{id}/members")
    public List<SuperAdminDtos.TeamAdminRow> teamMembers(@PathVariable Long id) {
        return service.listTeamMembers(id);
    }

    /**
     * One-shot: create an ADMIN account inside {@code teamId} without needing the caller
     * to switch into impersonation first. Rejects if the team already has an admin (see
     * {@link SuperAdminService#createTeamAdmin} — every admin runs their own team).
     */
    @PostMapping("/teams/{id}/admins")
    public ResponseEntity<SuperAdminDtos.TeamAdminRow> createTeamAdmin(
            @PathVariable Long id,
            @Valid @RequestBody SuperAdminDtos.CreateTeamAdminRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createTeamAdmin(id, req));
    }

    /**
     * Canonical admin onboarding: creates a fresh team and drops the new admin into it in
     * one call. Since every admin is a solo workspace, this is what the super admin UI
     * should call whenever a new person needs an admin role.
     */
    @PostMapping("/admins-with-team")
    public ResponseEntity<SuperAdminDtos.AdminWithTeamResponse> createAdminWithNewTeam(
            @Valid @RequestBody SuperAdminDtos.CreateAdminWithTeamRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createAdminWithNewTeam(req));
    }

    /**
     * Per-project analytics across every team: project name, owning team, all admins of
     * that team (even ones added later), the project's member list, and ticket counts.
     * Renders in the /super/projects page.
     */
    @GetMapping("/projects-breakdown")
    public List<SuperAdminDtos.ProjectBreakdown> projectsBreakdown() {
        return service.projectsBreakdown();
    }

    @GetMapping("/admins")
    public List<SuperAdminDtos.SuperAdminRow> listSuperAdmins() {
        return service.listSuperAdmins();
    }

    @PostMapping("/admins")
    public ResponseEntity<SuperAdminDtos.SuperAdminRow> createSuperAdmin(
            @Valid @RequestBody SuperAdminDtos.CreateSuperAdminRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createSuperAdmin(req));
    }
}
