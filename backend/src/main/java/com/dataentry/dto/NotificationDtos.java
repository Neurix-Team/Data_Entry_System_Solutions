package com.dataentry.dto;

import java.time.Instant;
import java.util.List;

public class NotificationDtos {

    public record Item(
            Long id,
            String type,
            String message,
            String refType,
            Long refId,
            Long projectId,
            Instant createdAt,
            Instant readAt
    ) {}

    /** Compact envelope so the caller can render the badge from {@code unread} without
     *  post-processing the items list. */
    public record Feed(
            List<Item> items,
            long unread
    ) {}
}
