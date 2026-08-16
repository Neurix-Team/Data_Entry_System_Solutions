package com.dataentry.service;

import com.dataentry.dto.TicketDtos;
import com.dataentry.model.*;
import com.dataentry.repository.CustomFieldRepository;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.ProjectRepository;
import com.dataentry.repository.SubcategoryRepository;
import com.dataentry.repository.TicketRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
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
    // Retained for the one-off resource-name translation path inside applyResources — every
    // other translation now goes through the preparer's dedup cache built up-front.
    private final TranslationService translator;
    private final Localizer localizer;
    private final AuditService audit;
    private final org.springframework.beans.factory.ObjectProvider<TicketDocumentService> documentServiceProvider;

    private final org.springframework.beans.factory.ObjectProvider<TicketService> selfProvider;

    // Kept here (as well as in TicketTranslationPreparer) because applyCustomValues below still
    // needs to know which field types roundtrip through the translator vs. mirror as-is.
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
        this.selfProvider = selfProvider;
    }

    public TicketDtos.TicketResponse create(User currentUser, TicketDtos.CreateTicketRequest req) {
        List<CustomField> fields = loadActiveFields(req.subcategoryId());
        Map<String, TranslationService.Bilingual> tr = translations.prepareForOne(
                req.title(), req.content(), req.websiteName(), req.customValues(), fields);
        return selfProvider.getObject().createTx(currentUser, req, fields, tr);
    }

    @Transactional
    public TicketDtos.TicketResponse createTx(User currentUser,
                                              TicketDtos.CreateTicketRequest req,
                                              List<CustomField> fields,
                                              Map<String, TranslationService.Bilingual> tr) {
        Subcategory sub = loadActiveSubcategory(req.subcategoryId());
        Department dept = resolveDepartment(req.departmentId(), sub);
        Project project = loadOptionalProject(req.projectId());

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

    public TicketDtos.BulkCreateResponse createMany(User currentUser, TicketDtos.BulkCreateRequest req) {
        List<CustomField> fields = loadActiveFields(req.subcategoryId());
        // Translate every article + the shared custom values in one pass, outside the DB tx.
        Map<String, TranslationService.Bilingual> tr = translations.prepareForBulk(
                req.articles(), req.customValues(), fields);
        return selfProvider.getObject().createManyTx(currentUser, req, fields, tr);
    }

    @Transactional
    public TicketDtos.BulkCreateResponse createManyTx(User currentUser,
                                                      TicketDtos.BulkCreateRequest req,
                                                      List<CustomField> fields,
                                                      Map<String, TranslationService.Bilingual> tr) {
        Subcategory sub = loadActiveSubcategory(req.subcategoryId());
        Department dept = resolveDepartment(req.departmentId(), sub);
        Project project = loadOptionalProject(req.projectId());

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

    /**
     * Fan-out to TicketDocumentService via the ObjectProvider so we don't recreate the
     * cycle-breaking indirection here. Called after each ticket is persisted so the moved
     * images point at a ticket that already has an id.
     */
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
        return projectRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project not found"));
    }

    private Department loadActiveDepartment(Long id) {
        Department dept = departmentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department not found"));
        if (!dept.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department is not active");
        }
        return dept;
    }

    private Subcategory loadActiveSubcategory(Long id) {
        Subcategory sub = subcategoryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subcategory not found"));
        if (!sub.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subcategory is not active");
        }
        return sub;
    }

    /**
     * Resolve the ticket's department. Department is now optional in the request — if the
     * caller sent one we validate it matches the subcategory; if they didn't, we take the
     * subcategory's own department. This lets the user pick a subcategory directly without
     * having to pre-select its parent department.
     */
    private Department resolveDepartment(Long departmentId, Subcategory sub) {
        Department subDept = sub.getDepartment();
        if (departmentId == null) {
            if (subDept == null || !subDept.isActive()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Subcategory's department is not active");
            }
            return subDept;
        }
        Department dept = loadActiveDepartment(departmentId);
        if (subDept == null || !subDept.getId().equals(dept.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Subcategory does not belong to the selected department");
        }
        return dept;
    }

    private Ticket buildTicket(User currentUser, Department dept, Subcategory sub, Project project,
                               String title, String content, String websiteName, String websiteLink,
                               Map<String, TranslationService.Bilingual> tr) {
        String cleanUrl = websiteLink == null ? "" : websiteLink.trim();
        if (!cleanUrl.isEmpty()) {
            validateUrl(cleanUrl);
        }
        // Empty string (not null) so pre-existing NOT NULL constraints on the SQLite table
        // don't reject inserts from legacy databases.
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

        TranslationService.Bilingual titleBi = translations.lookup(tr, cleanTitle);
        t.setTitleEn(titleBi.en());
        t.setTitleAr(titleBi.ar());
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
        Page<Ticket> p = ticketRepository.findAllBySubmittedByOrderBySubmittedAtDesc(user, PageRequest.of(page, size));
        return toPage(p);
    }

    @Transactional(readOnly = true)
    public TicketDtos.TicketPage listAll(int page, int size) {
        Page<Ticket> p = ticketRepository.findAllByOrderBySubmittedAtDesc(PageRequest.of(page, size));
        return toPage(p);
    }

    @Transactional(readOnly = true)
    public TicketDtos.TicketResponse getOne(Long id, User currentUser, boolean isAdmin) {
        Ticket t = ticketRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        if (!isAdmin && !t.getSubmittedBy().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return toDto(t);
    }

    @Transactional
    public TicketDtos.TicketResponse updateStatus(Long id, String status) {
        // Belt-and-braces authz — this method is only reachable through /api/admin/**, which
        // SecurityConfig already gates on ROLE_ADMIN.  If someone ever moves the mapping out
        // of /admin, this line stops non-admins from silently mutating tickets.
        assertAdminAuthenticated();
        Ticket t = ticketRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        String previous = t.getStatus().name();
        try {
            t.setStatus(TicketStatus.valueOf(status));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status");
        }
        Ticket saved = ticketRepository.save(t);
        audit.record(AuditService.Action.STATUS_CHANGE, AuditService.EntityType.TICKET,
                saved.getId(), previous + " -> " + saved.getStatus().name());
        return toDto(saved);
    }

    private void assertAdminAuthenticated() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.isAuthenticated()
                && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    @Transactional
    public void delete(Long id) {
        assertAdminAuthenticated();
        deleteInternal(id);
    }

    /**
     * Delete a ticket the caller owns. Rejects with 403 if the ticket belongs to someone else
     * or the caller is anonymous. Admins should use {@link #delete(Long)}; this path exists so
     * a regular user can roll back their own submission when a follow-up step (attachment
     * upload) fails.
     */
    @Transactional
    public void deleteOwn(Long id, User currentUser) {
        if (currentUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        Ticket t = ticketRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        if (!t.getSubmittedBy().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        deleteInternal(id);
    }

    private void deleteInternal(Long id) {
        if (!ticketRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found");
        }
        ticketRepository.deleteById(id);
        // Files on disk aren't managed by JPA — sweep them after the DB rows are gone.
        // ObjectProvider avoids a circular-dependency cycle with TicketDocumentService,
        // which depends on TicketRepository too. Null-safe for tests where the provider
        // isn't wired.
        if (documentServiceProvider != null) {
            TicketDocumentService docs = documentServiceProvider.getIfAvailable();
            if (docs != null) docs.purgeTicketDirectory(id);
        }
        audit.record(AuditService.Action.DELETE, AuditService.EntityType.TICKET, id, null);
    }

    private TicketDtos.TicketPage toPage(Page<Ticket> p) {
        return new TicketDtos.TicketPage(
                p.getContent().stream().map(this::toDto).toList(),
                p.getTotalElements(), p.getTotalPages(), p.getNumber(), p.getSize()
        );
    }

    private TicketDtos.TicketResponse toDto(Ticket t) {
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
                    || h.equals("169.254.169.254")           // cloud metadata
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
            default -> { /* no strict server check */ }
        }
    }
}
