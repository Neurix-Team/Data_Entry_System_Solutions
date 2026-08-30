package com.dataentry.service;

import com.dataentry.dto.DepartmentDtos;
import com.dataentry.model.Department;
import com.dataentry.model.Project;
import com.dataentry.model.Subcategory;
import com.dataentry.model.Ticket;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.ProjectRepository;
import com.dataentry.repository.SubcategoryRepository;
import com.dataentry.repository.TicketRepository;
import com.dataentry.security.TenantGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;

@Service
// Class-level readOnly so read methods sit inside a Spring tx and the TenantFilterAspect
// enables the tenant filter before Hibernate runs the JPQL — otherwise the TeamOwned
// @PostLoad guard would 404 on the first department from another team.
@Transactional(readOnly = true)
public class DepartmentService {

    private final DepartmentRepository repository;
    private final SubcategoryRepository subcategoryRepository;
    private final TicketRepository ticketRepository;
    private final ProjectRepository projectRepository;
    private final TranslationService translator;
    private final Localizer localizer;
    private final AuditService audit;
    private final ObjectProvider<SubcategoryService> subcategoryServiceProvider;

    public DepartmentService(DepartmentRepository repository,
                             SubcategoryRepository subcategoryRepository,
                             TicketRepository ticketRepository,
                             ProjectRepository projectRepository,
                             TranslationService translator,
                             Localizer localizer,
                             AuditService audit,
                             ObjectProvider<SubcategoryService> subcategoryServiceProvider) {
        this.repository = repository;
        this.subcategoryRepository = subcategoryRepository;
        this.ticketRepository = ticketRepository;
        this.projectRepository = projectRepository;
        this.translator = translator;
        this.localizer = localizer;
        this.audit = audit;
        this.subcategoryServiceProvider = subcategoryServiceProvider;
    }

    // The list methods run inside a read-only transaction so the lazy Department.project
    // proxy can still be resolved while toDto is copying out the project name — outside a
    // transaction Hibernate closes the session as soon as the repository call returns and
    // any downstream getNameEn()/getName() on the lazy Project throws LazyInitialization.

    @Transactional(readOnly = true)
    public List<DepartmentDtos.DepartmentResponse> listAll() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(Department::getName, String.CASE_INSENSITIVE_ORDER))
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DepartmentDtos.DepartmentResponse> listActive() {
        return repository.findAllByActiveTrueOrderByNameAsc().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<DepartmentDtos.DepartmentResponse> listActiveByProject(Long projectId) {
        return repository.findAllByActiveTrueAndProjectIdOrderByNameAsc(projectId)
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<DepartmentDtos.DepartmentResponse> listActiveByProjects(java.util.Collection<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) return List.of();
        return repository.findAllByActiveTrueAndProjectIdInOrderByNameAsc(projectIds)
                .stream().map(this::toDto).toList();
    }

    @Transactional
    public DepartmentDtos.DepartmentResponse create(DepartmentDtos.UpsertDepartmentRequest req) {
        String raw = req.name().trim();
        if (repository.existsByNameIgnoreCase(raw)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Department already exists");
        }
        Project project = requireProject(req.projectId());
        TranslationService.Bilingual bi = translator.toBoth(raw);
        Department d = Department.builder()
                .name(raw)
                .nameEn(bi.en())
                .nameAr(bi.ar())
                .active(req.active() == null || req.active())
                .project(project)
                .build();
        Department saved = repository.save(d);
        audit.record(AuditService.Action.CREATE, AuditService.EntityType.DEPARTMENT,
                saved.getId(), "name=" + raw + " projectId=" + project.getId());
        return toDto(saved);
    }

    @Transactional
    public DepartmentDtos.DepartmentResponse update(Long id, DepartmentDtos.UpsertDepartmentRequest req) {
        Department d = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found"));
        TenantGuard.assertOwnership(d);
        String newName = req.name().trim();
        if (!d.getName().equalsIgnoreCase(newName) && repository.existsByNameIgnoreCase(newName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Department already exists");
        }
        d.setName(newName);
        TranslationService.Bilingual bi = translator.toBoth(newName);
        d.setNameEn(bi.en());
        d.setNameAr(bi.ar());
        if (req.active() != null) d.setActive(req.active());
        d.setProject(requireProject(req.projectId()));
        Department saved = repository.save(d);
        audit.record(AuditService.Action.UPDATE, AuditService.EntityType.DEPARTMENT,
                saved.getId(), "name=" + newName + " active=" + saved.isActive()
                        + " projectId=" + saved.getProject().getId());
        return toDto(saved);
    }

    private Project requireProject(Long projectId) {
        if (projectId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A department must belong to a project");
        }
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Project not found"));
        TenantGuard.assertOwnership(project);
        return project;
    }

    @Transactional
    public void delete(Long id) {
        deleteWithChildren(id);
    }

    /**
     * Cascade-delete a department together with every subcategory, custom field, and
     * ticket living inside it. Called from the project delete path as well, which is why
     * it's public — a project delete wants the same tree wipe applied to each of its
     * departments.
     */
    @Transactional
    public void deleteWithChildren(Long id) {
        Department d = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found"));
        TenantGuard.assertOwnership(d);
        SubcategoryService subSvc = subcategoryServiceProvider.getObject();
        for (Subcategory s : subcategoryRepository.findAllByDepartmentId(id)) {
            subSvc.deleteWithChildren(s.getId());
        }
        // A ticket can point at a department without going through a subcategory (legacy
        // rows). Sweep any that are still hanging off this department after the subcategory
        // pass above. deleteAll tolerates already-removed rows.
        ticketRepository.deleteAll(ticketRepository.findAllByDepartmentId(id));
        repository.deleteById(id);
        audit.record(AuditService.Action.DELETE, AuditService.EntityType.DEPARTMENT, id, null);
    }

    private DepartmentDtos.DepartmentResponse toDto(Department d) {
        String localized = localizer.pick(d.getNameEn(), d.getNameAr(), d.getName());
        Project project = d.getProject();
        Long projectId = project == null ? null : project.getId();
        String projectName = project == null
                ? null
                : localizer.pick(project.getNameEn(), project.getNameAr(), project.getName());
        return new DepartmentDtos.DepartmentResponse(
                d.getId(), localized, d.getNameEn(), d.getNameAr(), d.isActive(),
                projectId, projectName);
    }
}
