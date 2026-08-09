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

    public SubcategoryService(SubcategoryRepository repository,
                              DepartmentRepository departmentRepository,
                              TicketRepository ticketRepository,
                              CustomFieldRepository customFieldRepository) {
        this.repository = repository;
        this.departmentRepository = departmentRepository;
        this.ticketRepository = ticketRepository;
        this.customFieldRepository = customFieldRepository;
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
        Subcategory s = Subcategory.builder()
                .department(dept)
                .name(name)
                .active(req.active() == null || req.active())
                .build();
        return toDto(repository.save(s));
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
        if (req.active() != null) s.setActive(req.active());
        return toDto(repository.save(s));
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
    }

    private SubcategoryDtos.SubcategoryResponse toDto(Subcategory s) {
        long tickets = ticketRepository.countBySubcategoryId(s.getId());
        long fields = customFieldRepository.countBySubcategoryId(s.getId());
        return new SubcategoryDtos.SubcategoryResponse(
                s.getId(),
                s.getDepartment().getId(),
                s.getDepartment().getName(),
                s.getName(),
                s.isActive(),
                tickets,
                fields
        );
    }
}
