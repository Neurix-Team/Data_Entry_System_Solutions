package com.dataentry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public class SuperAdminDtos {


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


    public record PersonRef(
            Long id,
            String username,
            String displayName
    ) {}

    public record ProjectBreakdown(
            Long projectId,
            String projectName,
            String projectNameEn,
            String projectNameAr,
            Long teamId,
            String teamName,
            String teamColor,
            List<PersonRef> teamAdmins,
            List<PersonRef> projectMembers,
            long ticketCount,
            long ticketsThisWeek,
            String status
    ) {}


    public record EnterTeamResponse(
            Long teamId,
            String teamSlug,
            String teamName,
            String header
    ) {}
}
