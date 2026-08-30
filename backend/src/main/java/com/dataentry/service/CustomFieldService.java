package com.dataentry.service;

import com.dataentry.dto.CustomFieldDtos;
import com.dataentry.model.CustomField;
import com.dataentry.model.FieldType;
import com.dataentry.model.Subcategory;
import com.dataentry.repository.CustomFieldRepository;
import com.dataentry.repository.SubcategoryRepository;
import com.dataentry.security.TenantGuard;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

@Service
// Class-level readOnly so read methods sit inside a Spring tx and the TenantFilterAspect
// enables the tenant filter before Hibernate runs the JPQL — otherwise the TeamOwned
// @PostLoad guard would 404 on the first field from another team.
@Transactional(readOnly = true)
public class CustomFieldService {

    private final CustomFieldRepository repository;
    private final SubcategoryRepository subcategoryRepository;
    private final TranslationService translator;
    private final Localizer localizer;
    private final AuditService audit;

    public CustomFieldService(CustomFieldRepository repository,
                              SubcategoryRepository subcategoryRepository,
                              TranslationService translator,
                              Localizer localizer,
                              AuditService audit) {
        this.repository = repository;
        this.subcategoryRepository = subcategoryRepository;
        this.translator = translator;
        this.localizer = localizer;
        this.audit = audit;
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
        applyTranslations(f, req.label().trim(), req.placeholder(), req.options());
        CustomField saved = repository.save(f);
        audit.record(AuditService.Action.CREATE, AuditService.EntityType.CUSTOM_FIELD,
                saved.getId(), "key=" + saved.getFieldKey() + " label=" + saved.getLabel() + " type=" + saved.getType());
        return toDto(saved);
    }

    @Transactional
    public CustomFieldDtos.FieldResponse update(Long id, CustomFieldDtos.UpsertFieldRequest req) {
        CustomField f = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Field not found"));
        TenantGuard.assertOwnership(f);
        Subcategory sub = loadSubcategory(req.subcategoryId());
        boolean movedSub = !f.getSubcategory().getId().equals(sub.getId());
        if (movedSub && repository.existsBySubcategoryIdAndFieldKeyIgnoreCase(sub.getId(), f.getFieldKey())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Field key already exists in the target subcategory");
        }
        // fieldKey immutable to keep referential integrity in ticket_field_values
        f.setSubcategory(sub);
        boolean labelChanged = !Objects.equals(f.getLabel(), req.label().trim());
        boolean placeholderChanged = !Objects.equals(f.getPlaceholder(), req.placeholder());
        boolean optionsChanged = !Objects.equals(f.getOptions(), req.options());
        f.setLabel(req.label().trim());
        f.setType(FieldType.valueOf(req.type()));
        if (req.required() != null) f.setRequired(req.required());
        if (req.displayOrder() != null) f.setDisplayOrder(req.displayOrder());
        f.setOptions(req.options());
        f.setPlaceholder(req.placeholder());
        if (req.active() != null) f.setActive(req.active());
        if (labelChanged || placeholderChanged || optionsChanged) {
            applyTranslations(f, req.label().trim(), req.placeholder(), req.options());
        }
        CustomField saved = repository.save(f);
        audit.record(AuditService.Action.UPDATE, AuditService.EntityType.CUSTOM_FIELD,
                saved.getId(), "label=" + saved.getLabel() + " active=" + saved.isActive());
        return toDto(saved);
    }

    private void applyTranslations(CustomField f, String label, String placeholder, String options) {
        TranslationService.Bilingual labelBi = translator.toBoth(label);
        f.setLabelEn(labelBi.en());
        f.setLabelAr(labelBi.ar());
        if (placeholder != null && !placeholder.isBlank()) {
            TranslationService.Bilingual phBi = translator.toBoth(placeholder);
            f.setPlaceholderEn(phBi.en());
            f.setPlaceholderAr(phBi.ar());
        } else {
            f.setPlaceholderEn(null);
            f.setPlaceholderAr(null);
        }
        if (options != null && !options.isBlank()) {
            TranslationService.Bilingual opBi = translator.toBoth(options);
            f.setOptionsEn(opBi.en());
            f.setOptionsAr(opBi.ar());
        } else {
            f.setOptionsEn(null);
            f.setOptionsAr(null);
        }
    }

    @Transactional
    public void delete(Long id) {
        CustomField existing = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Field not found"));
        TenantGuard.assertOwnership(existing);
        try {
            repository.deleteById(id);
            audit.record(AuditService.Action.DELETE, AuditService.EntityType.CUSTOM_FIELD, id, null);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete a field already used by tickets. Deactivate it instead.");
        }
    }

    private Subcategory loadSubcategory(Long id) {
        Subcategory sub = subcategoryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid subcategory"));
        TenantGuard.assertOwnership(sub);
        return sub;
    }

    private CustomFieldDtos.FieldResponse toDto(CustomField f) {
        Subcategory sub = f.getSubcategory();
        Long subId = sub == null ? null : sub.getId();
        String subName = sub == null ? null : localizer.pick(sub.getNameEn(), sub.getNameAr(), sub.getName());
        Long deptId = sub == null ? null : sub.getDepartment().getId();
        String deptName = sub == null ? null
                : localizer.pick(sub.getDepartment().getNameEn(), sub.getDepartment().getNameAr(), sub.getDepartment().getName());
        return new CustomFieldDtos.FieldResponse(
                f.getId(),
                subId,
                subName,
                deptId,
                deptName,
                f.getFieldKey(),
                localizer.pick(f.getLabelEn(), f.getLabelAr(), f.getLabel()),
                f.getLabelEn(),
                f.getLabelAr(),
                f.getType().name(),
                f.isRequired(),
                f.getDisplayOrder(),
                localizer.pick(f.getOptionsEn(), f.getOptionsAr(), f.getOptions()),
                f.getOptionsEn(),
                f.getOptionsAr(),
                localizer.pick(f.getPlaceholderEn(), f.getPlaceholderAr(), f.getPlaceholder()),
                f.getPlaceholderEn(),
                f.getPlaceholderAr(),
                f.isActive()
        );
    }
}
