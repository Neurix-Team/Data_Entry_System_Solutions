package com.dataentry.service;

import com.dataentry.dto.ProjectDtos;
import com.dataentry.model.*;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.ProjectRepository;
import com.dataentry.repository.TicketRepository;
import com.dataentry.repository.UserRepository;
import com.dataentry.security.TenantGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ProjectService {

    private final Clock clock;
    private final ProjectRepository repository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final TicketRepository ticketRepository;
    private final TranslationService translator;
    private final Localizer localizer;
    private final AuditService audit;
    private final ObjectProvider<DepartmentService> departmentServiceProvider;

    public ProjectService(Clock clock,
                          ProjectRepository repository,
                          DepartmentRepository departmentRepository,
                          UserRepository userRepository,
                          TicketRepository ticketRepository,
                          TranslationService translator,
                          Localizer localizer,
                          AuditService audit,
                          ObjectProvider<DepartmentService> departmentServiceProvider) {
        this.clock = clock;
        this.repository = repository;
        this.departmentRepository = departmentRepository;
        this.userRepository = userRepository;
        this.ticketRepository = ticketRepository;
        this.translator = translator;
        this.localizer = localizer;
        this.audit = audit;
        this.departmentServiceProvider = departmentServiceProvider;
    }

    @Transactional(readOnly = true)
    public List<ProjectDtos.ProjectResponse> list() {
        return toDtos(repository.findAllByOrderByCreatedAtDesc());
    }

    @Transactional(readOnly = true)
    public List<ProjectDtos.ProjectResponse> listForMember(Long userId) {
        if (userId == null) return List.of();
        return toDtos(repository.findAllByMemberId(userId));
    }

    private List<ProjectDtos.ProjectResponse> toDtos(List<Project> projects) {
        if (projects.isEmpty()) return List.of();
        List<Long> ids = projects.stream().map(Project::getId).toList();
        Map<Long, List<Department>> deptsByProject = new HashMap<>();
        for (Department d : departmentRepository.findAllByProjectIdIn(ids)) {
            if (d.getProject() != null) {
                deptsByProject.computeIfAbsent(d.getProject().getId(), k -> new ArrayList<>()).add(d);
            }
        }
        Map<Long, List<User>> membersByProject = preloadMembers(projects, ids);
        return projects.stream()
                .map(p -> toDto(p,
                        deptsByProject.getOrDefault(p.getId(), List.of()),
                        membersByProject.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    private Map<Long, List<User>> preloadMembers(List<Project> projects, List<Long> ids) {
        Map<Long, List<Long>> projectsByTeam = new HashMap<>();
        for (Project p : projects) {
            Long teamId = p.getTeam() == null ? null : p.getTeam().getId();
            if (teamId == null) continue;
            projectsByTeam.computeIfAbsent(teamId, k -> new ArrayList<>()).add(p.getId());
        }
        Map<Long, List<User>> out = new HashMap<>();
        for (Map.Entry<Long, List<Long>> e : projectsByTeam.entrySet()) {
            Long teamId = e.getKey();
            List<Long> projectIds = e.getValue();
            for (Object[] row : userRepository.findMembersOfProjectsInTeam(projectIds, teamId)) {
                Long pid = (Long) row[0];
                User u = (User) row[1];
                out.computeIfAbsent(pid, k -> new ArrayList<>()).add(u);
            }
        }
        return out;
    }

    @Transactional
    public ProjectDtos.ProjectResponse create(ProjectDtos.UpsertProjectRequest req) {
        List<Long> deptIds = effectiveDepartmentIds(req);
        List<Department> depts = deptIds.isEmpty() ? List.of() : loadDepartments(deptIds);
        Set<User> members = resolveMembers(req.memberIds());

        String name = req.name().trim();
        String subtitle = req.subtitle() != null ? req.subtitle().trim() : null;

        Project p = Project.builder()
                .name(name)
                .subtitle(subtitle)
                .department(depts.isEmpty() ? null : depts.get(0))
                .members(members)
                .startDate(req.startDate())
                .endDate(req.endDate())
                .progress(req.progress() == null ? 0 : req.progress())
                .status(req.status() == null ? ProjectStatus.ON_TRACK : ProjectStatus.valueOf(req.status()))
                .build();
        applyTranslations(p, name, subtitle);
        Project saved = repository.save(p);

        if (!depts.isEmpty()) {
            assignDepartmentsToProject(depts, saved);
        }

        audit.record(AuditService.Action.CREATE, AuditService.EntityType.PROJECT,
                saved.getId(), "name=" + name + " departments=" + depts.size());
        return toDto(saved);
    }

    @Transactional
    public ProjectDtos.ProjectResponse update(Long id, ProjectDtos.UpsertProjectRequest req) {
        Project p = repository.findWithMembersById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        TenantGuard.assertOwnership(p);

        String newName = req.name().trim();
        String newSubtitle = req.subtitle() != null ? req.subtitle().trim() : null;
        boolean nameChanged = !Objects.equals(p.getName(), newName);
        boolean subtitleChanged = !Objects.equals(p.getSubtitle(), newSubtitle);

        p.setName(newName);
        p.setSubtitle(newSubtitle);

        List<Long> targetIds = effectiveDepartmentIds(req);
        if (!targetIds.isEmpty()) {
            List<Department> targets = loadDepartments(targetIds);
            List<Department> current = departmentRepository.findAllByProjectId(id);
            Set<Long> targetIdSet = new HashSet<>(targetIds);
            for (Department d : current) {
                if (!targetIdSet.contains(d.getId())) {
                    d.setProject(null);
                    departmentRepository.save(d);
                }
            }
            assignDepartmentsToProject(targets, p);
            p.setDepartment(targets.get(0));
        }

        if (req.memberIds() != null) {
            p.setMembers(resolveMembers(req.memberIds()));
        }
        p.setStartDate(req.startDate());
        p.setEndDate(req.endDate());
        if (req.progress() != null) p.setProgress(req.progress());
        if (req.status() != null) p.setStatus(ProjectStatus.valueOf(req.status()));
        if (nameChanged || subtitleChanged) applyTranslations(p, newName, newSubtitle);

        Project saved = repository.save(p);
        audit.record(AuditService.Action.UPDATE, AuditService.EntityType.PROJECT,
                saved.getId(), "name=" + newName + " status=" + saved.getStatus());
        return toDto(saved);
    }

    private void applyTranslations(Project p, String name, String subtitle) {
        TranslationService.Bilingual nameBi = translator.toBoth(name);
        p.setNameEn(nameBi.en());
        p.setNameAr(nameBi.ar());
        if (subtitle != null && !subtitle.isBlank()) {
            TranslationService.Bilingual subBi = translator.toBoth(subtitle);
            p.setSubtitleEn(subBi.en());
            p.setSubtitleAr(subBi.ar());
        } else {
            p.setSubtitleEn(null);
            p.setSubtitleAr(null);
        }
    }

    /**
     * Soft delete — the project moves to the recycle bin. Departments, members and
     * every entry attached to it stay exactly as they are, so a restore is instant
     * and lossless; the permanent cascade only runs from the bin (RecycleBinService).
     */
    @Transactional
    public void delete(Long id) {
        Project p = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        TenantGuard.assertOwnership(p);
        if (p.getDeletedAt() != null) {
            // Already binned — behave exactly like a missing project.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        p.setDeletedAt(Instant.now());
        p.setDeletedById(currentActorId());
        repository.save(p);
        audit.record(AuditService.Action.DELETE, AuditService.EntityType.PROJECT,
                id, "soft; name=" + p.getName());
    }

    private Long currentActorId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        Object principal = auth.getPrincipal();
        return principal instanceof User u ? u.getId() : null;
    }


    private List<Long> effectiveDepartmentIds(ProjectDtos.UpsertProjectRequest req) {
        if (req.departmentIds() != null && !req.departmentIds().isEmpty()) {
            return req.departmentIds().stream().distinct().toList();
        }
        if (req.departmentId() != null) return List.of(req.departmentId());
        return List.of();
    }

    private List<Department> loadDepartments(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        List<Department> found = departmentRepository.findAllById(ids);
        if (found.size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "One or more departments not found");
        }
        found.forEach(TenantGuard::assertOwnership);
        found.sort(Comparator.comparingInt(d -> ids.indexOf(d.getId())));
        return found;
    }

    private void assignDepartmentsToProject(List<Department> depts, Project project) {
        for (Department d : depts) {
            d.setProject(project);
            departmentRepository.save(d);
        }
    }

    private Set<User> resolveMembers(Set<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) return new HashSet<>();
        List<User> found = userRepository.findAllById(memberIds);
        found.forEach(TenantGuard::assertOwnership);
        return new HashSet<>(found);
    }

    private ProjectDtos.ProjectResponse toDto(Project p) {
        Long teamId = p.getTeam() == null ? null : p.getTeam().getId();
        List<User> members = teamId == null
                ? List.of()
                : userRepository.findMembersOfProjectInTeam(p.getId(), teamId);
        return toDto(p, departmentRepository.findAllByProjectId(p.getId()), members);
    }

    private ProjectDtos.ProjectResponse toDto(Project p,
                                              List<Department> projectDepartments,
                                              List<User> projectMembers) {
        Integer daysLeft = null;
        if (p.getEndDate() != null && p.getStatus() != ProjectStatus.COMPLETED) {
            daysLeft = (int) ChronoUnit.DAYS.between(LocalDate.now(clock), p.getEndDate());
        }
        List<ProjectDtos.ProjectMember> members = projectMembers.stream()
                .map(u -> new ProjectDtos.ProjectMember(
                        u.getId(),
                        u.getUsername(),
                        localizer.pick(u.getDisplayNameEn(), u.getDisplayNameAr(), u.getDisplayName()),
                        u.getDisplayNameEn(),
                        u.getDisplayNameAr()))
                .toList();

        List<ProjectDtos.ProjectDepartment> depts = projectDepartments.stream()
                .map(d -> new ProjectDtos.ProjectDepartment(
                        d.getId(),
                        localizer.pick(d.getNameEn(), d.getNameAr(), d.getName()),
                        d.getNameEn(),
                        d.getNameAr()))
                .toList();

        Department legacy = p.getDepartment();
        return new ProjectDtos.ProjectResponse(
                p.getId(),
                localizer.pick(p.getNameEn(), p.getNameAr(), p.getName()),
                p.getNameEn(),
                p.getNameAr(),
                localizer.pick(p.getSubtitleEn(), p.getSubtitleAr(), p.getSubtitle()),
                p.getSubtitleEn(),
                p.getSubtitleAr(),
                legacy == null ? null : legacy.getId(),
                legacy == null ? null : localizer.pick(legacy.getNameEn(), legacy.getNameAr(), legacy.getName()),
                legacy == null ? null : legacy.getNameEn(),
                legacy == null ? null : legacy.getNameAr(),
                depts,
                members,
                p.getStartDate(), p.getEndDate(), daysLeft,
                p.getProgress(), p.getStatus().name()
        );
    }
}
