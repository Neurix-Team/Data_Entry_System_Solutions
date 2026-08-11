package com.dataentry.service;

import com.dataentry.dto.ProjectDtos;
import com.dataentry.model.*;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.ProjectRepository;
import com.dataentry.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class ProjectService {

    private final Clock clock;
    private final ProjectRepository repository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final TranslationService translator;
    private final Localizer localizer;
    private final AuditService audit;

    public ProjectService(Clock clock,
                          ProjectRepository repository,
                          DepartmentRepository departmentRepository,
                          UserRepository userRepository,
                          TranslationService translator,
                          Localizer localizer,
                          AuditService audit) {
        this.clock = clock;
        this.repository = repository;
        this.departmentRepository = departmentRepository;
        this.userRepository = userRepository;
        this.translator = translator;
        this.localizer = localizer;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ProjectDtos.ProjectResponse> list() {
        return repository.findAllByOrderByCreatedAtDesc().stream().map(this::toDto).toList();
    }

    @Transactional
    public ProjectDtos.ProjectResponse create(ProjectDtos.UpsertProjectRequest req) {
        List<Department> depts = loadDepartments(effectiveDepartmentIds(req));
        Set<User> members = resolveMembers(req.memberIds());

        String name = req.name().trim();
        String subtitle = req.subtitle() != null ? req.subtitle().trim() : null;

        // The legacy single-department pointer stays populated with the first department in
        // the picker so old DB constraints (department_id NOT NULL) and any code still reading
        // Project.department keep working.
        Project p = Project.builder()
                .name(name)
                .subtitle(subtitle)
                .department(depts.get(0))
                .members(members)
                .startDate(req.startDate())
                .endDate(req.endDate())
                .progress(req.progress() == null ? 0 : req.progress())
                .status(req.status() == null ? ProjectStatus.ON_TRACK : ProjectStatus.valueOf(req.status()))
                .build();
        applyTranslations(p, name, subtitle);
        Project saved = repository.save(p);

        // Point every selected department at this new project.
        assignDepartmentsToProject(depts, saved);

        audit.record(AuditService.Action.CREATE, AuditService.EntityType.PROJECT,
                saved.getId(), "name=" + name + " departments=" + depts.size());
        return toDto(saved);
    }

    @Transactional
    public ProjectDtos.ProjectResponse update(Long id, ProjectDtos.UpsertProjectRequest req) {
        Project p = repository.findWithMembersById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        String newName = req.name().trim();
        String newSubtitle = req.subtitle() != null ? req.subtitle().trim() : null;
        boolean nameChanged = !Objects.equals(p.getName(), newName);
        boolean subtitleChanged = !Objects.equals(p.getSubtitle(), newSubtitle);

        p.setName(newName);
        p.setSubtitle(newSubtitle);

        // Departments: replace the current set with the requested set. Any department that
        // used to belong to this project but isn't in the new list is unassigned (set to null).
        List<Long> targetIds = effectiveDepartmentIds(req);
        if (!targetIds.isEmpty()) {
            List<Department> targets = loadDepartments(targetIds);
            List<Department> current = departmentRepository.findAll().stream()
                    .filter(d -> d.getProject() != null && Objects.equals(d.getProject().getId(), id))
                    .toList();
            Set<Long> targetIdSet = new HashSet<>(targetIds);
            for (Department d : current) {
                if (!targetIdSet.contains(d.getId())) {
                    d.setProject(null);
                    departmentRepository.save(d);
                }
            }
            assignDepartmentsToProject(targets, p);
            // Keep the legacy pointer aligned with the first selected dept.
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

    @Transactional
    public void delete(Long id) {
        Project p = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        // Unassign every department pointing at this project before we delete it.
        departmentRepository.findAll().stream()
                .filter(d -> d.getProject() != null && Objects.equals(d.getProject().getId(), id))
                .forEach(d -> { d.setProject(null); departmentRepository.save(d); });
        repository.deleteById(p.getId());
        audit.record(AuditService.Action.DELETE, AuditService.EntityType.PROJECT, id, null);
    }

    // ---------- helpers ----------

    private List<Long> effectiveDepartmentIds(ProjectDtos.UpsertProjectRequest req) {
        if (req.departmentIds() != null && !req.departmentIds().isEmpty()) {
            return req.departmentIds().stream().distinct().toList();
        }
        // Backward compat: an old client that still sends the single departmentId.
        if (req.departmentId() != null) return List.of(req.departmentId());
        return List.of();
    }

    private List<Department> loadDepartments(List<Long> ids) {
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one department is required");
        }
        List<Department> found = departmentRepository.findAllById(ids);
        if (found.size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "One or more departments not found");
        }
        // Preserve request order so the first ID becomes the legacy primary.
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
        return new HashSet<>(found);
    }

    private ProjectDtos.ProjectResponse toDto(Project p) {
        Integer daysLeft = null;
        if (p.getEndDate() != null && p.getStatus() != ProjectStatus.COMPLETED) {
            daysLeft = (int) ChronoUnit.DAYS.between(LocalDate.now(clock), p.getEndDate());
        }
        List<ProjectDtos.ProjectMember> members = p.getMembers().stream()
                .map(u -> new ProjectDtos.ProjectMember(
                        u.getId(),
                        u.getUsername(),
                        localizer.pick(u.getDisplayNameEn(), u.getDisplayNameAr(), u.getDisplayName()),
                        u.getDisplayNameEn(),
                        u.getDisplayNameAr()))
                .toList();

        // Departments-in-project: query the child table since Project.departments is transient.
        List<ProjectDtos.ProjectDepartment> depts = new ArrayList<>();
        for (Department d : departmentRepository.findAll()) {
            if (d.getProject() != null && Objects.equals(d.getProject().getId(), p.getId())) {
                depts.add(new ProjectDtos.ProjectDepartment(
                        d.getId(),
                        localizer.pick(d.getNameEn(), d.getNameAr(), d.getName()),
                        d.getNameEn(),
                        d.getNameAr()));
            }
        }

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
