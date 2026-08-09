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

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ProjectService {

    private final ProjectRepository repository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;

    public ProjectService(ProjectRepository repository,
                          DepartmentRepository departmentRepository,
                          UserRepository userRepository) {
        this.repository = repository;
        this.departmentRepository = departmentRepository;
        this.userRepository = userRepository;
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

        Project p = Project.builder()
                .name(req.name().trim())
                .subtitle(req.subtitle() != null ? req.subtitle().trim() : null)
                .department(dept)
                .members(members)
                .startDate(req.startDate())
                .endDate(req.endDate())
                .progress(req.progress() == null ? 0 : req.progress())
                .status(req.status() == null ? ProjectStatus.ON_TRACK : ProjectStatus.valueOf(req.status()))
                .build();
        return toDto(repository.save(p));
    }

    @Transactional
    public ProjectDtos.ProjectResponse update(Long id, ProjectDtos.UpsertProjectRequest req) {
        Project p = repository.findWithMembersById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        p.setName(req.name().trim());
        p.setSubtitle(req.subtitle() != null ? req.subtitle().trim() : null);
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

        return toDto(repository.save(p));
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        repository.deleteById(id);
    }

    private Set<User> resolveMembers(Set<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) return new HashSet<>();
        List<User> found = userRepository.findAllById(memberIds);
        return new HashSet<>(found);
    }

    private ProjectDtos.ProjectResponse toDto(Project p) {
        Integer daysLeft = null;
        if (p.getEndDate() != null && p.getStatus() != ProjectStatus.COMPLETED) {
            daysLeft = (int) ChronoUnit.DAYS.between(LocalDate.now(), p.getEndDate());
        }
        List<ProjectDtos.ProjectMember> members = p.getMembers().stream()
                .map(u -> new ProjectDtos.ProjectMember(u.getId(), u.getUsername(), u.getDisplayName()))
                .toList();
        return new ProjectDtos.ProjectResponse(
                p.getId(), p.getName(), p.getSubtitle(),
                p.getDepartment().getId(), p.getDepartment().getName(),
                members,
                p.getStartDate(), p.getEndDate(), daysLeft,
                p.getProgress(), p.getStatus().name()
        );
    }
}
