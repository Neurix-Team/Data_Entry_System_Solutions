package com.dataentry.service;

import com.dataentry.dto.SearchDtos;
import com.dataentry.model.User;
import com.dataentry.repository.DepartmentRepository;
import com.dataentry.repository.ProjectRepository;
import com.dataentry.repository.SubcategoryRepository;
import com.dataentry.repository.TicketRepository;
import com.dataentry.repository.UserRepository;
import com.dataentry.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Global search behind the Ctrl+K palette. Every query is team-scoped (null teamId
 * only for super admins) and bounded — enough hits to navigate, never a data dump.
 */
@Service
public class SearchService {

    private static final int MIN_QUERY_LENGTH = 2;
    private static final int TICKET_LIMIT = 8;
    private static final int TYPE_LIMIT = 5;

    private final TicketRepository tickets;
    private final ProjectRepository projects;
    private final DepartmentRepository departments;
    private final SubcategoryRepository subcategories;
    private final UserRepository users;

    public SearchService(TicketRepository tickets,
                         ProjectRepository projects,
                         DepartmentRepository departments,
                         SubcategoryRepository subcategories,
                         UserRepository users) {
        this.tickets = tickets;
        this.projects = projects;
        this.departments = departments;
        this.subcategories = subcategories;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public SearchDtos.SearchResponse search(User current, String rawQuery) {
        String q = rawQuery == null ? "" : rawQuery.trim();
        if (q.length() < MIN_QUERY_LENGTH) {
            return SearchDtos.SearchResponse.empty(q);
        }
        String pattern = "%" + q.toLowerCase() + "%";
        Long teamId = TenantContext.isSuperAdmin() ? null : TenantContext.getTeamId();
        boolean admin = current != null && current.isAdminLike();

        List<SearchDtos.TicketHit> ticketHits = tickets.searchTickets(teamId, pattern, TICKET_LIMIT)
                .stream()
                .map(r -> new SearchDtos.TicketHit(r.getId(), r.getTitle(), r.getTitleEn(),
                        r.getTitleAr(), r.getStatus(), r.getSubmittedByUsername(), r.getSubmittedAt()))
                .toList();
        List<SearchDtos.ProjectHit> projectHits = projects.searchProjects(teamId, pattern, TYPE_LIMIT)
                .stream()
                .map(r -> new SearchDtos.ProjectHit(r.getId(), r.getName()))
                .toList();
        List<SearchDtos.DepartmentHit> departmentHits = departments
                .searchDepartments(teamId, pattern, TYPE_LIMIT)
                .stream()
                .map(r -> new SearchDtos.DepartmentHit(r.getId(), r.getName()))
                .toList();
        List<SearchDtos.SubcategoryHit> subcategoryHits = subcategories
                .searchSubcategories(teamId, pattern, TYPE_LIMIT)
                .stream()
                .map(r -> new SearchDtos.SubcategoryHit(r.getId(), r.getName()))
                .toList();
        List<SearchDtos.UserHit> userHits = admin
                ? users.searchUsers(teamId, pattern, TYPE_LIMIT).stream()
                        .map(r -> new SearchDtos.UserHit(r.getId(), r.getUsername(), r.getDisplayName()))
                        .toList()
                : List.of();

        return new SearchDtos.SearchResponse(q, ticketHits, projectHits,
                departmentHits, subcategoryHits, userHits);
    }
}