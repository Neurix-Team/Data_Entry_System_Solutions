package com.dataentry.service;

import com.dataentry.dto.TicketDtos;
import com.dataentry.model.*;
import com.dataentry.repository.CustomFieldRepository;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.SubcategoryRepository;
import com.dataentry.repository.TicketRepository;
import com.dataentry.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;


@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final DepartmentRepository departmentRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final CustomFieldRepository customFieldRepository;
    private final UserRepository userRepository;

    public TicketService(TicketRepository ticketRepository,
                         DepartmentRepository departmentRepository,
                         SubcategoryRepository subcategoryRepository,
                         CustomFieldRepository customFieldRepository,
                         UserRepository userRepository) {
        this.ticketRepository = ticketRepository;
        this.departmentRepository = departmentRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.customFieldRepository = customFieldRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public TicketDtos.TicketResponse create(User currentUser, TicketDtos.CreateTicketRequest req) {
        Department dept = loadActiveDepartment(req.departmentId());
        Subcategory sub = loadActiveSubcategory(req.subcategoryId(), dept);

        Ticket ticket = buildTicket(
                currentUser, dept, sub,
                req.title(), req.content(),
                req.websiteName(), req.websiteLink()
        );
        applyCustomValues(ticket, sub, req.customValues());
        return toDto(ticketRepository.save(ticket));
    }

    @Transactional
    public TicketDtos.BulkCreateResponse createMany(User currentUser, TicketDtos.BulkCreateRequest req) {
        Department dept = loadActiveDepartment(req.departmentId());
        Subcategory sub = loadActiveSubcategory(req.subcategoryId(), dept);

        List<TicketDtos.TicketResponse> saved = new ArrayList<>();
        for (TicketDtos.ArticleRequest article : req.articles()) {
            Ticket ticket = buildTicket(
                    currentUser, dept, sub,
                    article.title(), article.content(),
                    article.websiteName(), article.websiteLink()
            );
            applyCustomValues(ticket, sub, req.customValues());
            saved.add(toDto(ticketRepository.save(ticket)));
        }
        return new TicketDtos.BulkCreateResponse(saved.size(), saved);
    }

    private Department loadActiveDepartment(Long id) {
        Department dept = departmentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department not found"));
        if (!dept.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department is not active");
        }
        return dept;
    }

    private Subcategory loadActiveSubcategory(Long id, Department dept) {
        Subcategory sub = subcategoryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subcategory not found"));
        if (!sub.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subcategory is not active");
        }
        if (!sub.getDepartment().getId().equals(dept.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Subcategory does not belong to the selected department");
        }
        return sub;
    }

    private Ticket buildTicket(User currentUser, Department dept, Subcategory sub,
                               String title, String content, String websiteName, String websiteLink) {
        String cleanUrl = websiteLink == null ? "" : websiteLink.trim();
        if (!cleanUrl.isEmpty()) {
            validateUrl(cleanUrl);
        }
        // Empty string (not null) so pre-existing NOT NULL constraints on the SQLite table
        // don't reject inserts from legacy databases.
        String cleanName = websiteName == null ? "" : websiteName.trim();

        return Ticket.builder()
                .submittedBy(currentUser)
                .department(dept)
                .subcategory(sub)
                .title(title == null ? "" : title.trim())
                .content(content.trim())
                .websiteName(cleanName)
                .websiteLink(cleanUrl)
                .status(TicketStatus.IN_PROGRESS)
                .build();
    }

    private void applyCustomValues(Ticket ticket, Subcategory sub, Map<String, String> inputs) {
        List<CustomField> activeFields = customFieldRepository
                .findAllBySubcategoryIdAndActiveTrueOrderByDisplayOrderAscIdAsc(sub.getId());
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
            TicketFieldValue tfv = TicketFieldValue.builder()
                    .ticket(ticket)
                    .field(field)
                    .value(value == null ? "" : value)
                    .build();
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
        Ticket t = ticketRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        try {
            t.setStatus(TicketStatus.valueOf(status));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status");
        }
        return toDto(ticketRepository.save(t));
    }

    @Transactional
    public void delete(Long id) {
        if (!ticketRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found");
        }
        ticketRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> adminStats() {
        List<Ticket> all = ticketRepository.findAll();
        long inProgress = all.stream().filter(t -> t.getStatus() == TicketStatus.IN_PROGRESS).count();
        long review = all.stream().filter(t -> t.getStatus() == TicketStatus.REVIEW).count();
        long completed = all.stream().filter(t -> t.getStatus() == TicketStatus.COMPLETED).count();
        LocalDate today = LocalDate.now();
        long completedToday = all.stream()
                .filter(t -> t.getStatus() == TicketStatus.COMPLETED)
                .filter(t -> t.getSubmittedAt().atZone(ZoneId.systemDefault()).toLocalDate().equals(today))
                .count();

        Map<String, Object> m = new HashMap<>();
        m.put("totalTickets", all.size());
        m.put("totalDepartments", departmentRepository.count());
        m.put("activeFields", customFieldRepository.findAllByActiveTrueOrderByDisplayOrderAscIdAsc().size());
        m.put("totalUsers", userRepository.count());
        m.put("inProgress", inProgress);
        m.put("review", review);
        m.put("completed", completed);
        m.put("completedToday", completedToday);
        return m;
    }

    /**
     * Reports data — task distribution by weekday + top performers.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> report() {
        List<Ticket> all = ticketRepository.findAll();
        LocalDate today = LocalDate.now();
        LocalDate sixDaysAgo = today.minusDays(6);
        ZoneId zone = ZoneId.systemDefault();

        Map<String, Long> byDay = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            LocalDate d = sixDaysAgo.plusDays(i);
            byDay.put(d.toString(), 0L);
        }
        all.forEach(t -> {
            LocalDate ld = t.getSubmittedAt().atZone(zone).toLocalDate();
            if (!ld.isBefore(sixDaysAgo) && !ld.isAfter(today)) {
                byDay.merge(ld.toString(), 1L, Long::sum);
            }
        });

        Map<Long, Long> byUser = new HashMap<>();
        all.stream()
                .filter(t -> t.getStatus() == TicketStatus.COMPLETED)
                .forEach(t -> byUser.merge(t.getSubmittedBy().getId(), 1L, Long::sum));

        List<Map<String, Object>> topPerformers = byUser.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(e -> {
                    User u = userRepository.findById(e.getKey()).orElse(null);
                    Map<String, Object> row = new HashMap<>();
                    row.put("userId", e.getKey());
                    row.put("username", u == null ? "?" : u.getUsername());
                    row.put("displayName", u == null ? "?" : (u.getDisplayName() != null ? u.getDisplayName() : u.getUsername()));
                    row.put("completed", e.getValue());
                    return row;
                })
                .toList();

        long completedThisWeek = all.stream()
                .filter(t -> t.getStatus() == TicketStatus.COMPLETED)
                .filter(t -> {
                    LocalDate ld = t.getSubmittedAt().atZone(zone).toLocalDate();
                    return ChronoUnit.DAYS.between(ld, today) < 7;
                }).count();

        Map<String, Object> out = new HashMap<>();
        out.put("byDay", byDay);
        out.put("topPerformers", topPerformers);
        out.put("completedThisWeek", completedThisWeek);
        return out;
    }

    private TicketDtos.TicketPage toPage(Page<Ticket> p) {
        return new TicketDtos.TicketPage(
                p.getContent().stream().map(this::toDto).toList(),
                p.getTotalElements(), p.getTotalPages(), p.getNumber(), p.getSize()
        );
    }

    private TicketDtos.TicketResponse toDto(Ticket t) {
        User u = t.getSubmittedBy();
        List<TicketDtos.CustomValueResponse> customs = t.getCustomValues().stream()
                .map(v -> new TicketDtos.CustomValueResponse(
                        v.getField().getId(),
                        v.getField().getFieldKey(),
                        v.getField().getLabel(),
                        v.getValue()
                )).toList();
        return new TicketDtos.TicketResponse(
                t.getId(),
                t.getDepartment().getId(),
                t.getDepartment().getName(),
                t.getSubcategory() == null ? null : t.getSubcategory().getId(),
                t.getSubcategory() == null ? null : t.getSubcategory().getName(),
                t.getTitle(),
                t.getContent(),
                t.getWebsiteName(),
                t.getWebsiteLink(),
                t.getStatus().name(),
                t.getSubmittedAt(),
                u.getId(),
                u.getUsername(),
                u.getDisplayName(),
                customs
        );
    }

    private void validateUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Website link must start with http:// or https://");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Website link is missing a host");
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
