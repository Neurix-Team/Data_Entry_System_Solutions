package com.dataentry.dto;

import java.time.Instant;
import java.util.List;

/** Global search (Ctrl+K palette): bounded, grouped hits — not a report. */
public class SearchDtos {

    public record TicketHit(Long id, String title, String titleEn, String titleAr,
                            String status, String submittedByUsername, Instant submittedAt) {}

    public record ProjectHit(Long id, String name) {}

    public record DepartmentHit(Long id, String name) {}

    public record SubcategoryHit(Long id, String name) {}

    public record UserHit(Long id, String username, String displayName) {}

    public record SearchResponse(String query,
                                 List<TicketHit> tickets,
                                 List<ProjectHit> projects,
                                 List<DepartmentHit> departments,
                                 List<SubcategoryHit> subcategories,
                                 List<UserHit> users) {

        public static SearchResponse empty(String query) {
            return new SearchResponse(query, List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }
}