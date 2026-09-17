package com.dataentry.service;

import com.dataentry.dto.ChatMessagingDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Tracks authenticated /ws/chat sessions per user id (multi-device / multi-tab aware)
 * and pushes real-time envelopes to them. Attribute key {@link #ATTR_USER_ID} is set
 * by the handshake interceptor after JWT validation.
 */
@Service
public class ChatSocketSessionRegistry {

    public static final Logger log = LoggerFactory.getLogger(ChatSocketSessionRegistry.class);

    public static final String ATTR_USER_ID = "chatUserId";

    private final ObjectMapper mapper;
    private final Map<Long, CopyOnWriteArraySet<WebSocketSession>> byUser = new ConcurrentHashMap<>();

    public ChatSocketSessionRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void register(WebSocketSession session) {
        Long userId = userIdOf(session);
        if (userId == null) return;
        byUser.computeIfAbsent(userId, id -> new CopyOnWriteArraySet<>()).add(session);
        log.debug("Chat socket connected user={} (sessions={})", userId, byUser.get(userId).size());
    }

    public void unregister(WebSocketSession session) {
        Long userId = userIdOf(session);
        if (userId == null) return;
        Set<WebSocketSession> set = byUser.get(userId);
        if (set == null) return;
        set.remove(session);
        if (set.isEmpty()) byUser.remove(userId, set);
    }

    public boolean isOnline(Long userId) {
        Set<WebSocketSession> set = byUser.get(userId);
        if (set == null || set.isEmpty()) return false;
        return set.stream().anyMatch(WebSocketSession::isOpen);
    }

    /** Pushes the envelope to every open session of the user. Never throws. */
    public void push(Long userId, ChatMessagingDtos.WsOut payload) {
        if (userId == null || payload == null) return;
        Set<WebSocketSession> set = byUser.get(userId);
        if (set == null || set.isEmpty()) return;
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Failed to serialize chat push: {}", e.getMessage());
            return;
        }
        TextMessage text = new TextMessage(json);
        for (WebSocketSession s : set) {
            if (s == null || !s.isOpen()) continue;
            try {
                // sendMessage is not thread-safe per session — serialize sends.
                synchronized (s) {
                    s.sendMessage(text);
                }
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping dead chat session for user={}: {}", userId, e.toString());
            }
        }
    }

    public void pushToBoth(ChatConversationParticipants participants, ChatMessagingDtos.WsOut payload) {
        push(participants.userAId(), payload);
        if (!participants.userAId().equals(participants.userBId())) {
            push(participants.userBId(), payload);
        }
    }

    public record ChatConversationParticipants(Long userAId, Long userBId) {}

    private Long userIdOf(WebSocketSession session) {
        Object v = session.getAttributes().get(ATTR_USER_ID);
        return v instanceof Long l ? l : null;
    }
}
