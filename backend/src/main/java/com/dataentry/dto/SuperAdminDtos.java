package com.dataentry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Everything the super-admin surface exchanges with the frontend. Kept in one file so the
 * shape of the /api/super/* API is easy to read at a glance.
 */
public class SuperAdminDtos {

    // ---------- team CRUD ----------

    public record TeamSummary(
            Long id,
            String slug,
            String name,
            String nameEn,
            String nameAr,
            String description,
            String color,
            boolean active,
            Instant createdAt,
            long userCount,
            long adminCount,
            long projectCount,
            long departmentCount,
            long ticketCount,
            long ticketsThisWeek
    ) {}

    public record CreateTeamRequest(
            @NotBlank @Size(max = 60)
            @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,58}[a-z0-9]$",
                    message = "slug must be lowercase alphanumeric with dashes (2-60 chars)")
            String slug,
            @NotBlank @Size(max = 150) String name,
            @Size(max = 300) String description,
            @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "color must be a #RRGGBB hex value")
            String color
    ) {}

    public record UpdateTeamRequest(
            @NotBlank @Size(max = 150) String name,
            @Size(max = 300) String description,
            @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "color must be a #RRGGBB hex value")
            String color,
            Boolean active
    ) {}

    // ---------- super-admin overview ----------

    /**
     * KPIs shown on the super-admin landing page. Aggregated across every team; the same
     * numbers are also broken out per-team in {@link TeamSummary}.
     */
    public record OverviewStats(
            long totalTeams,
            long activeTeams,
            long totalUsers,
            long totalAdmins,
            long totalProjects,
            long totalDepartments,
            long totalTickets,
            long ticketsToday,
            long ticketsThisWeek,
            List<TeamSummary> teams
    ) {}

    // ---------- other super admins ----------

    public record SuperAdminRow(
            Long id,
            String username,
            String displayName,
            String email,
            boolean active,
            Instant createdAt
    ) {}

    public record CreateSuperAdminRequest(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(min = 8, max = 200) String password,
            @Size(max = 150) String displayName,
            @Size(max = 200) String email
    ) {}

    // ---------- team admin creation from super surface ----------

    /**
     * Request to seed an admin directly into a target team, without needing the super admin
     * to impersonate first. Used by the "Create team admin" button on the super Teams page —
     * a one-shot form that assigns the new admin to the correct team as it's created.
     */
    public record CreateTeamAdminRequest(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(min = 8, max = 200) String password,
            @Size(max = 150) String displayName,
            @Size(max = 200) String email
    ) {}

    public record TeamAdminRow(
            Long id,
            String username,
            String displayName,
            String email,
            String role,
            boolean active,
            Instant createdAt
    ) {}

    /**
     * One-shot payload for the "create an admin with their own fresh workspace" flow — the
     * canonical way to onboard an admin now that every admin runs an isolated team. Team
     * slug/name/color are optional; sensible defaults are derived from the admin's username.
     */
    public record CreateAdminWithTeamRequest(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(min = 8, max = 200) String password,
            @Size(max = 150) String displayName,
            @Size(max = 200) String email,
            @Size(max = 150) String teamName,
            @Size(max = 300) String teamDescription,
            @Size(max = 60)
            @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,58}[a-z0-9]$",
                    message = "team slug must be lowercase alphanumeric with dashes (2-60 chars)")
            String teamSlug,
            @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "color must be a #RRGGBB hex value")
            String teamColor
    ) {}

    public record AdminWithTeamResponse(
            TeamSummary team,
            TeamAdminRow admin
    ) {}

    // ---------- per-project analytics ----------

    public record PersonRef(
            Long id,
            String username,
            String displayName
    ) {}

    /**
     * Everything the "who works on this project" analytics view needs, joined server-side so
     * the UI renders one flat table without follow-up round-trips per row.
     */
    public record ProjectBreakdown(
            Long projectId,
            String projectName,
            String projectNameEn,
            String projectNameAr,
            Long teamId,
            String teamName,
            String teamColor,
            /** Every ADMIN in the owning team — includes admins seeded by other admins later on. */
            List<PersonRef> teamAdmins,
            /** Users explicitly attached to the project as members. */
            List<PersonRef> projectMembers,
            long ticketCount,
            long ticketsThisWeek,
            String status
    ) {}

    // ---------- impersonation ----------

    /**
     * Response to {@code POST /api/super/teams/{id}/enter}. The frontend stores the returned
     * team id and starts sending {@code X-Impersonate-Team-Id} on every subsequent API call
     * until the user clicks "Exit". No new JWT is issued — the header is enough because the
     * JwtAuthFilter recognises SUPER_ADMIN + this header and enables the tenant filter.
     */
    public record EnterTeamResponse(
            Long teamId,
            String teamSlug,
            String teamName,
            String header
    ) {}
}
