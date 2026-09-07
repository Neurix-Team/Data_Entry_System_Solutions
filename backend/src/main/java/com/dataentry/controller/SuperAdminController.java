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

    @PostMapping("/teams/{id}/enter")
    public SuperAdminDtos.EnterTeamResponse enterTeam(@PathVariable Long id) {
        return service.enterTeam(id);
    }

    @GetMapping("/teams/{id}/members")
    public List<SuperAdminDtos.TeamAdminRow> teamMembers(@PathVariable Long id) {
        return service.listTeamMembers(id);
    }

    @PostMapping("/teams/{id}/admins")
    public ResponseEntity<SuperAdminDtos.TeamAdminRow> createTeamAdmin(
            @PathVariable Long id,
            @Valid @RequestBody SuperAdminDtos.CreateTeamAdminRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createTeamAdmin(id, req));
    }

    @PostMapping("/admins-with-team")
    public ResponseEntity<SuperAdminDtos.AdminWithTeamResponse> createAdminWithNewTeam(
            @Valid @RequestBody SuperAdminDtos.CreateAdminWithTeamRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createAdminWithNewTeam(req));
    }

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
