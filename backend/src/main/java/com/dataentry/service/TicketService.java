package com.dataentry.service;

import com.dataentry.dto.TicketDtos;
import com.dataentry.model.*;
import com.dataentry.repository.CustomFieldRepository;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.ProjectRepository;
import com.dataentry.repository.SubcategoryRepository;
import com.dataentry.repository.TicketRepository;
import com.dataentry.security.TenantGuard;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final DepartmentRepository departmentRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final ProjectRepository projectRepository;
    private final CustomFieldRepository customFieldRepository;
    private final TicketTranslationPreparer translations;
    private final TranslationService translator;
    private final Localizer localizer;
    private final AuditService audit;
    private final org.springframework.beans.factory.ObjectProvider<TicketDocumentService> documentServiceProvider;
    private final org.springframework.beans.factory.ObjectProvider<NotificationService> notificationServiceProvider;

    private final org.springframework.beans.factory.ObjectProvider<TicketService> selfProvider;

    private static final Set<FieldType> TRANSLATABLE_TYPES =
            EnumSet.of(FieldType.TEXT, FieldType.TEXTAREA, FieldType.SELECT);

    public TicketService(TicketRepository ticketRepository,
                         DepartmentRepository departmentRepository,
                         SubcategoryRepository subcategoryRepository,
                         ProjectRepository projectRepository,
                         CustomFieldRepository customFieldRepository,
                         TicketTranslationPreparer translations,
                         TranslationService translator,
                         Localizer localizer,
                         AuditService audit,
                         org.springframework.beans.factory.ObjectProvider<TicketDocumentService> documentServiceProvider,
                         org.springframework.beans.factory.ObjectProvider<NotificationService> notificationServiceProvider,
                         org.springframework.beans.factory.ObjectProvider<TicketService> selfProvider) {
        this.ticketRepository = ticketRepository;
        this.departmentRepository = departmentRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.projectRepository = projectRepository;
        this.customFieldRepository = customFieldRepository;
        this.translations = translations;
        this.translator = translator;
        this.localizer = localizer;
        this.audit = audit;
        this.documentServiceProvider = documentServiceProvider;
        this.notificationServiceProvider = notificationServiceProvider;
        this.selfProvider = selfProvider;
    }

    public TicketDtos.TicketResponse create(User currentUser, TicketDtos.CreateTicketRequest req) {
        List<CustomField> fields = loadActiveFields(req.subcategoryId());
        Map<String, TranslationService.Bilingual> tr = translations.prepareForOne(
                req.content(), req.websiteName(), req.customValues(), fields);
        return selfProvider.getObject().createTx(currentUser, req, fields, tr);
    }

    @Transactional
    public TicketDtos.TicketResponse createTx(User currentUser,
                                              TicketDtos.CreateTicketRequest req,
                                              List<CustomField> fields,
                                              Map<String, TranslationService.Bilingual> tr) {
        Project project = loadOptionalProject(req.projectId());
        Subcategory sub = loadOptionalActiveSubcategory(req.subcategoryId());
        Department dept = resolveDepartment(req.departmentId(), sub, project);

        Ticket ticket = buildTicket(
                currentUser, dept, sub, project,
                req.title(), req.content(),
                req.websiteName(), req.websiteLink(), tr
        );
        applyCustomValues(ticket, fields, req.customValues(), tr);
        applyResources(ticket, req.resources());
        Ticket saved = ticketRepository.save(ticket);
        promoteExtractedImages(saved, req.extractedImages(), currentUser);
        return toDto(saved);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Ticket createAttachmentTicket(User currentUser, Long projectId, Long departmentId, String title) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project not found"));
        TenantGuard.assertOwnership(project);
        if (project.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project not found");
        }
        Department dept = (departmentId != null)
                ? resolveRequestedDepartment(project, departmentId)
                : resolveDepartmentForQuickUpload(project);
        String cleanTitle = title == null ? "" : title.trim();
        Ticket t = Ticket.builder()
                .submittedBy(currentUser)
                .department(dept)
                .project(project)
                .title(cleanTitle)
                .content("")
                .websiteName("")
                .websiteLink("")
                .status(TicketStatus.REVIEW)
                .build();
        t.setTitleEn(cleanTitle);
        t.setTitleAr(cleanTitle);
        t.setContentEn("");
        t.setContentAr("");
        t.setWebsiteNameEn("");
        t.setWebsiteNameAr("");
        return ticketRepository.save(t);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void deleteByIdUnchecked(Long ticketId) {
        if (ticketRepository.existsById(ticketId)) {
            ticketRepository.deleteById(ticketId);
        }
    }

    private Department resolveRequestedDepartment(Project project, Long departmentId) {
        Department dept = departmentRepository.findById(departmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Department not found"));
        TenantGuard.assertOwnership(dept);
        Long deptProjectId = dept.getProject() != null ? dept.getProject().getId() : null;
        if (deptProjectId == null || !deptProjectId.equals(project.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That department does not belong to this project.");
        }
        return dept;
    }

    private Department resolveDepartmentForQuickUpload(Project project) {
        Department legacy = project.getDepartment();
        if (legacy != null) return legacy;
        List<Department> active = departmentRepository
                .findAllByActiveTrueAndProjectIdOrderByNameAsc(project.getId());
        if (!active.isEmpty()) return active.get(0);
        List<Department> any = departmentRepository.findAllByProjectId(project.getId());
        if (!any.isEmpty()) return any.get(0);
        return createDefaultDepartmentFor(project);
    }

    private Department createDefaultDepartmentFor(Project project) {
        String base = project.getName() == null || project.getName().trim().isEmpty()
                ? "General"
                : project.getName().trim();
        String name = base;
        if (departmentRepository.existsByNameIgnoreCase(name)) {
            name = base + " (#" + project.getId() + ")";
        }
        TranslationService.Bilingual bi = translator.toBoth(name);
        Department d = Department.builder()
                .name(name)
                .nameEn(bi.en())
                .nameAr(bi.ar())
                .active(true)
                .project(project)
                .build();
        Department saved = departmentRepository.save(d);
        project.setDepartment(saved);
        projectRepository.save(project);
        audit.record(AuditService.Action.CREATE, AuditService.EntityType.DEPARTMENT,
                saved.getId(), "auto-created for project " + project.getId());
        return saved;
    }

    public TicketDtos.BulkCreateResponse createMany(User currentUser, TicketDtos.BulkCreateRequest req) {
        List<CustomField> fields = loadActiveFields(req.subcategoryId());
        Map<String, TranslationService.Bilingual> tr = translations.prepareForBulk(
                req.articles(), req.customValues(), fields);
        return selfProvider.getObject().createManyTx(currentUser, req, fields, tr);
    }

    @Transactional
    public TicketDtos.BulkCreateResponse createManyTx(User currentUser,
                                                      TicketDtos.BulkCreateRequest req,
                                                      List<CustomField> fields,
                                                      Map<String, TranslationService.Bilingual> tr) {
        Project project = loadOptionalProject(req.projectId());
        Subcategory sub = loadOptionalActiveSubcategory(req.subcategoryId());
        Department dept = resolveDepartment(req.departmentId(), sub, project);

        List<TicketDtos.TicketResponse> saved = new ArrayList<>();
        for (TicketDtos.ArticleRequest article : req.articles()) {
            Ticket ticket = buildTicket(
                    currentUser, dept, sub, project,
                    article.title(), article.content(),
                    article.websiteName(), article.websiteLink(), tr
            );
            applyCustomValues(ticket, fields, req.customValues(), tr);
            applyResources(ticket, article.resources());
            Ticket persisted = ticketRepository.save(ticket);
            promoteExtractedImages(persisted, article.extractedImages(), currentUser);
            saved.add(toDto(persisted));
        }
        return new TicketDtos.BulkCreateResponse(saved.size(), saved);
    }

    private List<CustomField> loadActiveFields(Long subcategoryId) {
        if (subcategoryId == null) return List.of();
        return customFieldRepository
                .findAllBySubcategoryIdAndActiveTrueOrderByDisplayOrderAscIdAsc(subcategoryId);
    }

    private void promoteExtractedImages(Ticket ticket,
                                        List<TicketDtos.ExtractedImageRef> images,
                                        User currentUser) {
        if (images == null || images.isEmpty() || documentServiceProvider == null) return;
        TicketDocumentService docs = documentServiceProvider.getIfAvailable();
        if (docs == null) return;
        docs.attachExtractedImages(ticket, images, currentUser);
    }

    private void applyResources(Ticket ticket, List<TicketDtos.ResourceRequest> resources) {
        if (resources == null || resources.isEmpty()) return;
        int order = 0;
        for (TicketDtos.ResourceRequest r : resources) {
            String url = r.url() == null ? "" : r.url().trim();
            if (url.isEmpty()) continue;
            validateUrl(url);
            String name = r.name() == null ? "" : r.name().trim();
            TicketResource res = TicketResource.builder()
                    .ticket(ticket)
                    .name(name)
                    .url(url)
                    .displayOrder(order++)
                    .build();
            if (!name.isBlank()) {
                TranslationService.Bilingual nameBi = translator.toBoth(name);
                res.setNameEn(nameBi.en());
                res.setNameAr(nameBi.ar());
            } else {
                res.setNameEn(name);
                res.setNameAr(name);
            }
            ticket.getResources().add(res);
        }
    }

    private Project loadOptionalProject(Long id) {
        if (id == null) return null;
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project not found"));
        TenantGuard.assertOwnership(project);
        if (project.getDeletedAt() != null) {
            // Binned project — submitting into it must fail like a missing one.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project not found");
        }
        return project;
    }

    private Department loadActiveDepartment(Long id) {
        Department dept = departmentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department not found"));
        TenantGuard.assertOwnership(dept);
        if (!dept.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department is not active");
        }
        return dept;
    }

    private Subcategory loadOptionalActiveSubcategory(Long id) {
        if (id == null) return null;
        Subcategory sub = subcategoryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subcategory not found"));
        TenantGuard.assertOwnership(sub);
        if (!sub.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subcategory is not active");
        }
        return sub;
    }

    private Department resolveDepartment(Long departmentId, Subcategory sub, Project project) {
        if (departmentId != null) {
            Department dept = loadActiveDepartment(departmentId);
            if (sub != null) {
                Department subDept = sub.getDepartment();
                if (subDept == null || !subDept.getId().equals(dept.getId())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Subcategory does not belong to the selected department");
                }
            }
            return dept;
        }
        if (sub != null) {
            Department subDept = sub.getDepartment();
            if (subDept == null || !subDept.isActive()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Subcategory's department is not active");
            }
            return subDept;
        }
        if (project != null) {
            return departmentRepository
                    .findAllByActiveTrueAndProjectIdOrderByNameAsc(project.getId())
                    .stream()
                    .findFirst()
                    .orElseGet(() -> createDefaultDepartmentFor(project));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Pick a project, department, or subcategory before submitting.");
    }

    private Ticket buildTicket(User currentUser, Department dept, Subcategory sub, Project project,
                               String title, String content, String websiteName, String websiteLink,
                               Map<String, TranslationService.Bilingual> tr) {
        String cleanUrl = websiteLink == null ? "" : websiteLink.trim();
        if (!cleanUrl.isEmpty()) {
            validateUrl(cleanUrl);
        }
        String cleanName = websiteName == null ? "" : websiteName.trim();
        String cleanTitle = title == null ? "" : title.trim();
        String cleanContent = content == null ? "" : content.trim();

        Ticket t = Ticket.builder()
                .submittedBy(currentUser)
                .department(dept)
                .subcategory(sub)
                .project(project)
                .title(cleanTitle)
                .content(cleanContent)
                .websiteName(cleanName)
                .websiteLink(cleanUrl)
                .status(TicketStatus.IN_PROGRESS)
                .build();

        t.setTitleEn(cleanTitle);
        t.setTitleAr(cleanTitle);
        TranslationService.Bilingual contentBi = translations.lookup(tr, cleanContent);
        t.setContentEn(contentBi.en());
        t.setContentAr(contentBi.ar());
        if (!cleanName.isBlank()) {
            TranslationService.Bilingual nameBi = translations.lookup(tr, cleanName);
            t.setWebsiteNameEn(nameBi.en());
            t.setWebsiteNameAr(nameBi.ar());
        }
        return t;
    }

    private void applyCustomValues(Ticket ticket, List<CustomField> activeFields,
                                   Map<String, String> inputs,
                                   Map<String, TranslationService.Bilingual> tr) {
        Map<String, String> values = inputs == null ? Map.of() : inputs;

        for (CustomField field : activeFields) {
            String value = values.getOrDefault(field.getFieldKey(), "");
            if (field.isRequired() && (value == null || value.isBlank())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Field '" + field.getLabel() + "' is required");
            }
            if (value != null && !value.isBlank()) {
                validateFieldValue(field, value);
            }
            String stored = value == null ? "" : value;
            TicketFieldValue tfv = TicketFieldValue.builder()
                    .ticket(ticket)
                    .field(field)
                    .value(stored)
                    .build();
            if (TRANSLATABLE_TYPES.contains(field.getType()) && !stored.isBlank()) {
                TranslationService.Bilingual bi = translations.lookup(tr, stored);
                tfv.setValueEn(bi.en());
                tfv.setValueAr(bi.ar());
            } else {
                tfv.setValueEn(stored);
                tfv.setValueAr(stored);
            }
            ticket.getCustomValues().add(tfv);
        }
    }

    @Transactional(readOnly = true)
    public TicketDtos.TicketPage listForUser(User user, int page, int size) {
        Page<Long> ids = ticketRepository.findUserPageIds(user.getId(), PageRequest.of(page, size));
        return toPage(ids);
    }

    @Transactional(readOnly = true)
    public TicketDtos.TicketPage listAll(int page, int size) {
        Page<Long> ids = ticketRepository.findAdminPageIds(PageRequest.of(page, size));
        return toPage(ids);
    }

    @Transactional(readOnly = true)
    public TicketDtos.TicketResponse getOne(Long id, User currentUser, boolean isAdmin) {
        Ticket t = ticketRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        TenantGuard.assertOwnership(t);
        if (!isAdmin && !t.getSubmittedBy().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return toDto(t);
    }

    @Transactional
    public TicketDtos.TicketResponse updateStatus(Long id, String status) {
        assertAdminAuthenticated();
        Ticket t = ticketRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        TenantGuard.assertOwnership(t);
        String previous = t.getStatus().name();
        try {
            t.setStatus(TicketStatus.valueOf(status));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status");
        }
        Ticket saved = ticketRepository.save(t);
        audit.record(AuditService.Action.STATUS_CHANGE, AuditService.EntityType.TICKET,
                saved.getId(), previous + " -> " + saved.getStatus().name());

        if (saved.getStatus() == TicketStatus.COMPLETED
                && !TicketStatus.COMPLETED.name().equals(previous)) {
            NotificationService notify = notificationServiceProvider == null
                    ? null : notificationServiceProvider.getIfAvailable();
            if (notify != null) {
                User submitter = saved.getSubmittedBy();
                String titlePreview = saved.getTitle() == null || saved.getTitle().isBlank()
                        ? "#" + saved.getId()
                        : saved.getTitle();
                Long projectId = saved.getProject() == null ? null : saved.getProject().getId();
                notify.emit(
                        submitter,
                        "TICKET_APPROVED",
                        "Your ticket \"" + titlePreview + "\" was approved and saved.",
                        "TICKET",
                        saved.getId(),
                        projectId
                );
            }
        }

        return toDto(saved);
    }

    @Transactional
    public TicketDtos.BulkApproveResponse approveMany(List<Long> ticketIds) {
        assertAdminAuthenticated();
        List<TicketDtos.TicketResponse> approved = new ArrayList<>();
        int changed = 0;
        for (Long id : new LinkedHashSet<>(ticketIds)) {
            Ticket ticket = ticketRepository.findWithDetailsById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Ticket not found: " + id));
            TenantGuard.assertOwnership(ticket);
            if (ticket.getStatus() == TicketStatus.COMPLETED) {
                approved.add(toDto(ticket));
            } else {
                approved.add(updateStatus(id, "COMPLETED"));
                changed++;
            }
        }
        return new TicketDtos.BulkApproveResponse(changed, approved);
    }

    public TicketDtos.TicketResponse updateByAdmin(Long id, TicketDtos.UpdateTicketRequest req) {
        assertAdminAuthenticated();
        Map<String, TranslationService.Bilingual> tr = translations.prepareForOne(
                req.content(), req.websiteName(), Map.of(), List.of());
        return selfProvider.getObject().updateByAdminTx(id, req, tr);
    }

    @Transactional
    public TicketDtos.TicketResponse updateByAdminTx(Long id,
                                                     TicketDtos.UpdateTicketRequest req,
                                                     Map<String, TranslationService.Bilingual> tr) {
        assertAdminAuthenticated();
        Ticket t = ticketRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        TenantGuard.assertOwnership(t);

        String cleanTitle = req.title() == null ? "" : req.title().trim();
        String cleanContent = req.content() == null ? "" : req.content().trim();
        String cleanName = req.websiteName() == null ? "" : req.websiteName().trim();
        String cleanUrl = req.websiteLink() == null ? "" : req.websiteLink().trim();
        if (!cleanUrl.isEmpty()) {
            validateUrl(cleanUrl);
        }

        t.setTitle(cleanTitle);
        t.setTitleEn(cleanTitle);
        t.setTitleAr(cleanTitle);

        t.setContent(cleanContent);
        TranslationService.Bilingual contentBi = translations.lookup(tr, cleanContent);
        t.setContentEn(contentBi.en());
        t.setContentAr(contentBi.ar());

        t.setWebsiteName(cleanName);
        TranslationService.Bilingual nameBi = translations.lookup(tr, cleanName);
        t.setWebsiteNameEn(nameBi.en());
        t.setWebsiteNameAr(nameBi.ar());
        t.setWebsiteLink(cleanUrl);

        if (req.resources() != null) {
            t.getResources().clear();
            applyResources(t, req.resources());
        }

        Ticket saved = ticketRepository.save(t);
        audit.record(AuditService.Action.UPDATE, AuditService.EntityType.TICKET, id, null);
        return toDto(saved);
    }

    /** Standard admin guard — also hands back the acting principal for the audit trail. */
    private User assertAdminAuthenticated() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.isAuthenticated()
                && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Object principal = auth == null ? null : auth.getPrincipal();
        return principal instanceof User u ? u : null;
    }

    @Transactional
    public void delete(Long id) {
        User actor = assertAdminAuthenticated();
        deleteInternal(id, actor);
    }

    @Transactional
    public void deleteOwn(Long id, User currentUser) {
        if (currentUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        Ticket t = ticketRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        TenantGuard.assertOwnership(t);
        if (!t.getSubmittedBy().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        deleteInternal(id, currentUser);
    }

    /**
     * Soft delete — the entry moves to the recycle bin: invisible to every read path
     * (entity-level @Where), fully recoverable until the retention sweep. Files and
     * child rows are deliberately untouched so a restore brings the entry back whole.
     */
    private void deleteInternal(Long id, User actor) {
        Ticket t = ticketRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        TenantGuard.assertOwnership(t);
        t.setDeletedAt(Instant.now());
        t.setDeletedById(actor == null ? null : actor.getId());
        ticketRepository.save(t);
        audit.record(AuditService.Action.DELETE, AuditService.EntityType.TICKET, id,
                "soft; title=" + (t.getTitle() == null ? "" : t.getTitle()));
    }

    private TicketDtos.TicketPage toPage(Page<Long> p) {
        if (p.isEmpty()) {
            return new TicketDtos.TicketPage(
                    List.of(), p.getTotalElements(), p.getTotalPages(), p.getNumber(), p.getSize());
        }
        Map<Long, Ticket> byId = ticketRepository.findListDetailsByIdIn(p.getContent()).stream()
                .collect(java.util.stream.Collectors.toMap(Ticket::getId, ticket -> ticket));
        List<TicketDtos.TicketResponse> items = p.getContent().stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .map(this::toDto)
                .toList();
        return new TicketDtos.TicketPage(
                items,
                p.getTotalElements(), p.getTotalPages(), p.getNumber(), p.getSize()
        );
    }

    TicketDtos.TicketResponse toDto(Ticket t) {
        User u = t.getSubmittedBy();
        Department dept = t.getDepartment();
        Subcategory sub = t.getSubcategory();
        Project proj = t.getProject();
        List<TicketDtos.CustomValueResponse> customs = t.getCustomValues().stream()
                .map(v -> {
                    CustomField f = v.getField();
                    return new TicketDtos.CustomValueResponse(
                            f.getId(),
                            f.getFieldKey(),
                            localizer.pick(f.getLabelEn(), f.getLabelAr(), f.getLabel()),
                            f.getLabelEn(),
                            f.getLabelAr(),
                            localizer.pick(v.getValueEn(), v.getValueAr(), v.getValue()),
                            v.getValueEn(),
                            v.getValueAr()
                    );
                }).toList();
        List<TicketDtos.ResourceResponse> resources = t.getResources().stream()
                .map(r -> new TicketDtos.ResourceResponse(
                        r.getId(),
                        localizer.pick(r.getNameEn(), r.getNameAr(), r.getName()),
                        r.getNameEn(),
                        r.getNameAr(),
                        r.getUrl(),
                        r.getDisplayOrder()
                )).toList();
        List<TicketDtos.DocumentResponse> documents = t.getDocuments().stream()
                .map(d -> new TicketDtos.DocumentResponse(
                        d.getId(),
                        d.getName(),
                        d.getOriginalFilename(),
                        d.getContentType(),
                        d.getSizeBytes(),
                        d.getUploadedAt()
                )).toList();
        return new TicketDtos.TicketResponse(
                t.getId(),
                dept.getId(),
                localizer.pick(dept.getNameEn(), dept.getNameAr(), dept.getName()),
                dept.getNameEn(),
                dept.getNameAr(),
                sub == null ? null : sub.getId(),
                sub == null ? null : localizer.pick(sub.getNameEn(), sub.getNameAr(), sub.getName()),
                sub == null ? null : sub.getNameEn(),
                sub == null ? null : sub.getNameAr(),
                proj == null ? null : proj.getId(),
                proj == null ? null : proj.getName(),
                localizer.pick(t.getTitleEn(), t.getTitleAr(), t.getTitle()),
                t.getTitleEn(),
                t.getTitleAr(),
                localizer.pick(t.getContentEn(), t.getContentAr(), t.getContent()),
                t.getContentEn(),
                t.getContentAr(),
                localizer.pick(t.getWebsiteNameEn(), t.getWebsiteNameAr(), t.getWebsiteName()),
                t.getWebsiteNameEn(),
                t.getWebsiteNameAr(),
                t.getWebsiteLink(),
                t.getStatus().name(),
                t.getSubmittedAt(),
                u.getId(),
                u.getUsername(),
                localizer.pick(u.getDisplayNameEn(), u.getDisplayNameAr(), u.getDisplayName()),
                u.getDisplayNameEn(),
                u.getDisplayNameAr(),
                customs,
                resources,
                documents
        );
    }

    private void validateUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Website link must start with http:// or https://");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Website link is missing a host");
            }
 
            String h = host.toLowerCase();
            boolean isPrivate = h.equals("localhost")
                    || h.equals("0.0.0.0")
                    || h.equals("169.254.169.254")
                    || h.startsWith("127.")
                    || h.startsWith("10.")
                    || h.startsWith("192.168.")
                    || h.startsWith("169.254.")
                    || h.matches("^172\\.(1[6-9]|2[0-9]|3[01])\\..*")
                    || h.endsWith(".local")
                    || h.endsWith(".internal");
            if (isPrivate) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Website link must point to a public address");
            }
        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Website link is not a valid URL");
        }
    }

    private void validateFieldValue(CustomField field, String value) {
        switch (field.getType()) {
            case NUMBER -> {
                try { Double.parseDouble(value); }
                catch (NumberFormatException e) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Field '" + field.getLabel() + "' must be a number");
                }
            }
            case URL -> validateUrl(value);
            case EMAIL -> {
                if (!value.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Field '" + field.getLabel() + "' must be a valid email");
                }
            }
            default -> {  }
        }
    }
}
