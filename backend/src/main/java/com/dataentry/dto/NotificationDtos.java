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

    public record Feed(
            List<Item> items,
            long unread
    ) {}
}
