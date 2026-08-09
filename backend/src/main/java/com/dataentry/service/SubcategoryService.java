package com.dataentry.service;

import com.dataentry.dto.SubcategoryDtos;
import com.dataentry.model.Department;
import com.dataentry.model.Subcategory;
import com.dataentry.repository.CustomFieldRepository;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.SubcategoryRepository;
import com.dataentry.repository.TicketRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class SubcategoryService {

    private final SubcategoryRepository repository;
    private final DepartmentRepository departmentRepository;
    private final TicketRepository ticketRepository;
    private final CustomFieldRepository customFieldRepository;
    private final TranslationService translator;
    private final Localizer localizer;
    private final AuditService audit;

    public SubcategoryService(SubcategoryRepository repository,
                              DepartmentRepository departmentRepository,
                              TicketRepository ticketRepository,
                              CustomFieldRepository customFieldRepository,
                              TranslationService translator,
                              Localizer localizer,
                              AuditService audit) {
        this.repository = repository;
        this.departmentRepository = departmentRepository;
        this.ticketRepository = ticketRepository;
        this.customFieldRepository = customFieldRepository;
        this.translator = translator;
        this.localizer = localizer;
        this.audit = audit;
    }

    public List<SubcategoryDtos.SubcategoryResponse> listAll(Long departmentId, boolean activeOnly) {
        List<Subcategory> rows;
        if (departmentId != null) {
            rows = activeOnly
                    ? repository.findAllByDepartmentIdAndActiveTrueOrderByNameAsc(departmentId)
                    : repository.findAllByDepartmentIdOrderByNameAsc(departmentId);
        } else {
            rows = activeOnly
                    ? repository.findAllByActiveTrueOrderByDepartmentIdAscNameAsc()
                    : repository.findAllByOrderByDepartmentIdAscNameAsc();
        }
        return rows.stream().map(this::toDto).toList();
    }

    public SubcategoryDtos.SubcategoryResponse getOne(Long id) {
        Subcategory s = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subcategory not found"));
        return toDto(s);
    }

    @Transactional
    public SubcategoryDtos.SubcategoryResponse create(SubcategoryDtos.UpsertSubcategoryRequest req) {
        Department dept = departmentRepository.findById(req.departmentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid department"));
        String name = req.name().trim();
        if (repository.existsByDepartmentIdAndNameIgnoreCase(dept.getId(), name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Subcategory already exists in this department");
        }
        TranslationService.Bilingual bi = translator.toBoth(name);
        Subcategory s = Subcategory.builder()
                .department(dept)
                .name(name)
                .nameEn(bi.en())
                .nameAr(bi.ar())
                .active(req.active() == null || req.active())
                .build();
        Subcategory saved = repository.save(s);
        audit.record(AuditService.Action.CREATE, AuditService.EntityType.SUBCATEGORY,
                saved.getId(), "name=" + name + " departmentId=" + dept.getId());
        return toDto(saved);
    }

    @Transactional
    public SubcategoryDtos.SubcategoryResponse update(Long id, SubcategoryDtos.UpsertSubcategoryRequest req) {
        Subcategory s = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subcategory not found"));
        Department dept = departmentRepository.findById(req.departmentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid department"));
        String newName = req.name().trim();
        boolean movedDept = !s.getDepartment().getId().equals(dept.getId());
        boolean renamed = !s.getName().equalsIgnoreCase(newName);
        if ((movedDept || renamed)
                && repository.existsByDepartmentIdAndNameIgnoreCase(dept.getId(), newName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Subcategory already exists in this department");
        }
        s.setDepartment(dept);
        s.setName(newName);
        if (renamed) {
            TranslationService.Bilingual bi = translator.toBoth(newName);
            s.setNameEn(bi.en());
            s.setNameAr(bi.ar());
        }
        if (req.active() != null) s.setActive(req.active());
        Subcategory saved = repository.save(s);
        audit.record(AuditService.Action.UPDATE, AuditService.EntityType.SUBCATEGORY,
                saved.getId(), "name=" + newName + " active=" + saved.isActive());
        return toDto(saved);
    }

    @Transactional
    public void delete(Long id) {
        Subcategory s = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subcategory not found"));
        long tickets = ticketRepository.countBySubcategoryId(id);
        long fields = customFieldRepository.countBySubcategoryId(id);
        if (tickets > 0 || fields > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete a subcategory with fields or tickets. Deactivate it instead.");
        }
        repository.delete(s);
        audit.record(AuditService.Action.DELETE, AuditService.EntityType.SUBCATEGORY, id, "name=" + s.getName());
    }

    private SubcategoryDtos.SubcategoryResponse toDto(Subcategory s) {
        long tickets = ticketRepository.countBySubcategoryId(s.getId());
        long fields = customFieldRepository.countBySubcategoryId(s.getId());
        Department d = s.getDepartment();
        return new SubcategoryDtos.SubcategoryResponse(
                s.getId(),
                d.getId(),
                localizer.pick(d.getNameEn(), d.getNameAr(), d.getName()),
                d.getNameEn(),
                d.getNameAr(),
                localizer.pick(s.getNameEn(), s.getNameAr(), s.getName()),
                s.getNameEn(),
                s.getNameAr(),
                s.isActive(),
                tickets,
                fields
        );
    }
}
