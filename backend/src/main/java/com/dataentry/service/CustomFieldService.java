package com.dataentry.service;

import com.dataentry.dto.CustomFieldDtos;
import com.dataentry.model.CustomField;
import com.dataentry.model.FieldType;
import com.dataentry.model.Subcategory;
import com.dataentry.repository.CustomFieldRepository;
import com.dataentry.repository.SubcategoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class CustomFieldService {

    private final CustomFieldRepository repository;
    private final SubcategoryRepository subcategoryRepository;

    public CustomFieldService(CustomFieldRepository repository,
                              SubcategoryRepository subcategoryRepository) {
        this.repository = repository;
        this.subcategoryRepository = subcategoryRepository;
    }

    public List<CustomFieldDtos.FieldResponse> listAll(Long subcategoryId) {
        List<CustomField> rows = subcategoryId == null
                ? repository.findAllByOrderByDisplayOrderAscIdAsc()
                : repository.findAllBySubcategoryIdOrderByDisplayOrderAscIdAsc(subcategoryId);
        return rows.stream().map(this::toDto).toList();
    }

    public List<CustomFieldDtos.FieldResponse> listActive(Long subcategoryId) {
        List<CustomField> rows = subcategoryId == null
                ? repository.findAllByActiveTrueOrderByDisplayOrderAscIdAsc()
                : repository.findAllBySubcategoryIdAndActiveTrueOrderByDisplayOrderAscIdAsc(subcategoryId);
        return rows.stream().map(this::toDto).toList();
    }

    @Transactional
    public CustomFieldDtos.FieldResponse create(CustomFieldDtos.UpsertFieldRequest req) {
        Subcategory sub = loadSubcategory(req.subcategoryId());
        if (repository.existsBySubcategoryIdAndFieldKeyIgnoreCase(sub.getId(), req.fieldKey().trim())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Field key already exists in this subcategory");
        }
        CustomField f = CustomField.builder()
                .subcategory(sub)
                .fieldKey(req.fieldKey().trim())
                .label(req.label().trim())
                .type(FieldType.valueOf(req.type()))
                .required(req.required() == null || req.required())
                .displayOrder(req.displayOrder() == null ? 0 : req.displayOrder())
                .options(req.options())
                .placeholder(req.placeholder())
                .active(req.active() == null || req.active())
                .build();
        return toDto(repository.save(f));
    }

    @Transactional
    public CustomFieldDtos.FieldResponse update(Long id, CustomFieldDtos.UpsertFieldRequest req) {
        CustomField f = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Field not found"));
        Subcategory sub = loadSubcategory(req.subcategoryId());
        boolean movedSub = !f.getSubcategory().getId().equals(sub.getId());
        if (movedSub && repository.existsBySubcategoryIdAndFieldKeyIgnoreCase(sub.getId(), f.getFieldKey())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Field key already exists in the target subcategory");
        }
        // fieldKey immutable to keep referential integrity in ticket_field_values
        f.setSubcategory(sub);
        f.setLabel(req.label().trim());
        f.setType(FieldType.valueOf(req.type()));
        if (req.required() != null) f.setRequired(req.required());
        if (req.displayOrder() != null) f.setDisplayOrder(req.displayOrder());
        f.setOptions(req.options());
        f.setPlaceholder(req.placeholder());
        if (req.active() != null) f.setActive(req.active());
        return toDto(repository.save(f));
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Field not found");
        }
        try {
            repository.deleteById(id);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete a field already used by tickets. Deactivate it instead.");
        }
    }

    private Subcategory loadSubcategory(Long id) {
        return subcategoryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid subcategory"));
    }

    private CustomFieldDtos.FieldResponse toDto(CustomField f) {
        Subcategory sub = f.getSubcategory();
        Long subId = sub == null ? null : sub.getId();
        String subName = sub == null ? null : sub.getName();
        Long deptId = sub == null ? null : sub.getDepartment().getId();
        String deptName = sub == null ? null : sub.getDepartment().getName();
        return new CustomFieldDtos.FieldResponse(
                f.getId(),
                subId,
                subName,
                deptId,
                deptName,
                f.getFieldKey(), f.getLabel(), f.getType().name(),
                f.isRequired(), f.getDisplayOrder(), f.getOptions(),
                f.getPlaceholder(), f.isActive()
        );
    }
}
