package com.dataentry.service;

import com.dataentry.dto.DepartmentDtos;
import com.dataentry.model.Department;
import com.dataentry.repository.DepartmentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;

@Service
public class DepartmentService {

    private final DepartmentRepository repository;
    private final TranslationService translator;
    private final Localizer localizer;
    private final AuditService audit;

    public DepartmentService(DepartmentRepository repository,
                             TranslationService translator,
                             Localizer localizer,
                             AuditService audit) {
        this.repository = repository;
        this.translator = translator;
        this.localizer = localizer;
        this.audit = audit;
    }

    public List<DepartmentDtos.DepartmentResponse> listAll() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(Department::getName, String.CASE_INSENSITIVE_ORDER))
                .map(this::toDto)
                .toList();
    }

    public List<DepartmentDtos.DepartmentResponse> listActive() {
        return repository.findAllByActiveTrueOrderByNameAsc().stream().map(this::toDto).toList();
    }

    public List<DepartmentDtos.DepartmentResponse> listActiveByProject(Long projectId) {
        return repository.findAllByActiveTrueAndProjectIdOrderByNameAsc(projectId)
                .stream().map(this::toDto).toList();
    }

    
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
        TranslationService.Bilingual bi = translator.toBoth(raw);
        Department d = Department.builder()
                .name(raw)
                .nameEn(bi.en())
                .nameAr(bi.ar())
                .active(req.active() == null || req.active())
                .build();
        Department saved = repository.save(d);
        audit.record(AuditService.Action.CREATE, AuditService.EntityType.DEPARTMENT,
                saved.getId(), "name=" + raw);
        return toDto(saved);
    }

    @Transactional
    public DepartmentDtos.DepartmentResponse update(Long id, DepartmentDtos.UpsertDepartmentRequest req) {
        Department d = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found"));
        String newName = req.name().trim();
        if (!d.getName().equalsIgnoreCase(newName) && repository.existsByNameIgnoreCase(newName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Department already exists");
        }
        d.setName(newName);
        TranslationService.Bilingual bi = translator.toBoth(newName);
        d.setNameEn(bi.en());
        d.setNameAr(bi.ar());
        if (req.active() != null) d.setActive(req.active());
        Department saved = repository.save(d);
        audit.record(AuditService.Action.UPDATE, AuditService.EntityType.DEPARTMENT,
                saved.getId(), "name=" + newName + " active=" + saved.isActive());
        return toDto(saved);
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found");
        }
        try {
            repository.deleteById(id);
            audit.record(AuditService.Action.DELETE, AuditService.EntityType.DEPARTMENT, id, null);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete a department that has tickets. Deactivate it instead.");
        }
    }

    private DepartmentDtos.DepartmentResponse toDto(Department d) {
        String localized = localizer.pick(d.getNameEn(), d.getNameAr(), d.getName());
        return new DepartmentDtos.DepartmentResponse(d.getId(), localized, d.getNameEn(), d.getNameAr(), d.isActive());
    }
}
