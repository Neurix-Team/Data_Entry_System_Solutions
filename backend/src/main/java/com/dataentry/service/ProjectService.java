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
        Department dept = departmentRepository.findById(req.departmentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department not found"));

        Set<User> members = resolveMembers(req.memberIds());

        String name = req.name().trim();
        String subtitle = req.subtitle() != null ? req.subtitle().trim() : null;
        Project p = Project.builder()
                .name(name)
                .subtitle(subtitle)
                .department(dept)
                .members(members)
                .startDate(req.startDate())
                .endDate(req.endDate())
                .progress(req.progress() == null ? 0 : req.progress())
                .status(req.status() == null ? ProjectStatus.ON_TRACK : ProjectStatus.valueOf(req.status()))
                .build();
        applyTranslations(p, name, subtitle);
        Project saved = repository.save(p);
        audit.record(AuditService.Action.CREATE, AuditService.EntityType.PROJECT,
                saved.getId(), "name=" + name + " departmentId=" + dept.getId());
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
        if (req.departmentId() != null && !req.departmentId().equals(p.getDepartment().getId())) {
            Department dept = departmentRepository.findById(req.departmentId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department not found"));
            p.setDepartment(dept);
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
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        repository.deleteById(id);
        audit.record(AuditService.Action.DELETE, AuditService.EntityType.PROJECT, id, null);
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
        Department dept = p.getDepartment();
        return new ProjectDtos.ProjectResponse(
                p.getId(),
                localizer.pick(p.getNameEn(), p.getNameAr(), p.getName()),
                p.getNameEn(),
                p.getNameAr(),
                localizer.pick(p.getSubtitleEn(), p.getSubtitleAr(), p.getSubtitle()),
                p.getSubtitleEn(),
                p.getSubtitleAr(),
                dept.getId(),
                localizer.pick(dept.getNameEn(), dept.getNameAr(), dept.getName()),
                dept.getNameEn(),
                dept.getNameAr(),
                members,
                p.getStartDate(), p.getEndDate(), daysLeft,
                p.getProgress(), p.getStatus().name()
        );
    }
}
