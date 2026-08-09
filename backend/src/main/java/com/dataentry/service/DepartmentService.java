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

    public DepartmentService(DepartmentRepository repository) {
        this.repository = repository;
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

    @Transactional
    public DepartmentDtos.DepartmentResponse create(DepartmentDtos.UpsertDepartmentRequest req) {
        if (repository.existsByNameIgnoreCase(req.name().trim())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Department already exists");
        }
        Department d = Department.builder()
                .name(req.name().trim())
                .active(req.active() == null || req.active())
                .build();
        return toDto(repository.save(d));
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
        if (req.active() != null) d.setActive(req.active());
        return toDto(repository.save(d));
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found");
        }
        try {
            repository.deleteById(id);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete a department that has tickets. Deactivate it instead.");
        }
    }

    private DepartmentDtos.DepartmentResponse toDto(Department d) {
        return new DepartmentDtos.DepartmentResponse(d.getId(), d.getName(), d.isActive());
    }
}
