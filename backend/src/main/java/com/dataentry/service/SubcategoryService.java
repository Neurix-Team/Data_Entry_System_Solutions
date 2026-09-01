package com.dataentry.service;

import com.dataentry.dto.SubcategoryDtos;
import com.dataentry.model.CustomField;
import com.dataentry.model.Department;
import com.dataentry.model.Subcategory;
import com.dataentry.model.Ticket;
import com.dataentry.repository.CustomFieldRepository;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.SubcategoryRepository;
import com.dataentry.repository.TicketRepository;
import com.dataentry.security.TenantGuard;
import com.dataentry.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
// Class-level readOnly so every method runs inside a Spring tx and the TenantFilterAspect
// enables the tenant filter before any query — without it the filter is skipped and the
// TeamOwned @PostLoad guard rejects the first cross-team row it sees, turning legitimate
// list requests into confusing 404s.
@Transactional(readOnly = true)
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
        // teamId may be null when a SUPER_ADMIN calls without impersonating a team;
        // the native query then returns rows across every team (pre-refactor behavior).
        Long teamId = TenantContext.getTeamId();
        return repository.findAdminListRows(teamId, departmentId, activeOnly).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * All active subcategories whose parent department belongs to any of the given projects.
     * Used by the submit form to show a user only the categories that live inside a project
     * they were assigned to, without requiring them to pre-pick a department. Wrapped in a
     * read-only transaction so the lazy Department.project proxy resolves while filtering.
     */
    @Transactional(readOnly = true)
    public List<SubcategoryDtos.SubcategoryResponse> listActiveByProjects(java.util.Collection<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) return List.of();
        return repository.findAllByActiveTrueOrderByDepartmentIdAscNameAsc().stream()
                .filter(s -> {
                    Department d = s.getDepartment();
                    return d != null && d.getProject() != null
                            && projectIds.contains(d.getProject().getId());
                })
                .map(this::toDto)
                .toList();
    }

    public SubcategoryDtos.SubcategoryResponse getOne(Long id) {
        Subcategory s = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subcategory not found"));
        TenantGuard.assertOwnership(s);
        return toDto(s);
    }

    @Transactional
    public SubcategoryDtos.SubcategoryResponse create(SubcategoryDtos.UpsertSubcategoryRequest req) {
        Department dept = departmentRepository.findById(req.departmentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid department"));
        TenantGuard.assertOwnership(dept);
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
        TenantGuard.assertOwnership(s);
        Department dept = departmentRepository.findById(req.departmentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid department"));
        TenantGuard.assertOwnership(dept);
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
        deleteWithChildren(id);
    }

    /**
     * Cascade-delete a subcategory along with every custom field and ticket living inside
     * it. Public so the department cascade path can call it directly — a subcategory that
     * still had rows attached used to block a department delete with a 409, forcing the
     * admin to hunt down and hand-clear each child before retrying.
     */
    @Transactional
    public void deleteWithChildren(Long id) {
        Subcategory s = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subcategory not found"));
        TenantGuard.assertOwnership(s);
        // deleteAll(...) tolerates entities that are already gone (e.g. cascaded away by a
        // prior delete in this same transaction) instead of throwing
        // EmptyResultDataAccessException the way deleteById does.
        ticketRepository.deleteAll(ticketRepository.findAllBySubcategoryId(id));
        customFieldRepository.deleteAll(
                customFieldRepository.findAllBySubcategoryIdOrderByDisplayOrderAscIdAsc(id));
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

    private SubcategoryDtos.SubcategoryResponse toDto(SubcategoryRepository.ListRow row) {
        return new SubcategoryDtos.SubcategoryResponse(
                row.getId(),
                row.getDepartmentId(),
                localizer.pick(row.getDepartmentNameEn(), row.getDepartmentNameAr(), row.getDepartmentName()),
                row.getDepartmentNameEn(),
                row.getDepartmentNameAr(),
                localizer.pick(row.getNameEn(), row.getNameAr(), row.getName()),
                row.getNameEn(),
                row.getNameAr(),
                Boolean.TRUE.equals(row.getActive()),
                row.getTicketCount(),
                row.getFieldCount()
        );
    }
}
