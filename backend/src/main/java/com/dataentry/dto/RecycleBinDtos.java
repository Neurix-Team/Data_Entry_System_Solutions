package com.dataentry.dto;

import java.time.Instant;
import java.util.List;

/**
 * Recycle bin payloads — binned tickets (data entries) and projects as one shape so
 * the frontend can render both tabs from a single component.
 */
public class RecycleBinDtos {

    /** @param kind TICKET or PROJECT — lets one list render both bin halves. */
    public record BinItem(
            Long id,
            String kind,
            String title,
            String titleEn,
            String titleAr,
            /** Ticket status (IN_PROGRESS/REVIEW/COMPLETED) or project status — display only. */
            String status,
            /** Submitting user (tickets); null for projects. */
            String ownerUsername,
            Instant deletedAt,
            /** Who binned it, for the bin's "deleted by" column. */
            String deletedByName,
            /** Days left before the retention sweep purges it for good. */
            long daysLeft
    ) {}

    public record BinPage(
            List<BinItem> items,
            long totalItems,
            int totalPages,
            int page,
            int size
    ) {}
}