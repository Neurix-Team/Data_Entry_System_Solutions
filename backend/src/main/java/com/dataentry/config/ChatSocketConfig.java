package com.dataentry.config;

import com.dataentry.model.Role;
import com.dataentry.model.User;
import com.dataentry.repository.UserRepository;
import com.dataentry.dto.ChatMessagingDtos;
import com.dataentry.security.JwtService;
import com.dataentry.service.ChatSocketSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.WebUtils;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Native (non-STOMP) WebSocket endpoint at /ws/chat. Handshake authenticates via the
 * same JWT used by HTTP (Authorization-style token in the ?token= query param, or the
 * dems_auth cookie) — browsers do not send Authorization headers on WS upgrades.
 * Token version, account state and team state are revalidated exactly like JwtAuthFilter.
 */
@Configuration
@EnableWebSocket
public class ChatSocketConfig implements WebSocketConfigurer {

    public static final String ENDPOINT = "/ws/chat";

    private final ChatHandshakeInterceptor handshake;
    private final ChatSocketHandler handler;

    public ChatSocketConfig(ChatHandshakeInterceptor handshake, ChatSocketHandler handler) {
        this.handshake = handshake;
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, ENDPOINT)
                .addInterceptors(handshake)
                .setAllowedOriginPatterns("*"); // origin itself is authenticated by JWT
    }
// __PART2__

    @Component
    public static class ChatHandshakeInterceptor implements HandshakeInterceptor {

        private static final Logger log = LoggerFactory.getLogger(ChatHandshakeInterceptor.class);

        private final JwtService jwtService;
        private final UserRepository userRepository;

        public ChatHandshakeInterceptor(JwtService jwtService, UserRepository userRepository) {
            this.jwtService = jwtService;
            this.userRepository = userRepository;
        }

        @Override
        public boolean beforeHandshake(@NonNull ServerHttpRequest request,
                                       @NonNull ServerHttpResponse response,
                                       @NonNull WebSocketHandler wsHandler,
                                       @NonNull Map<String, Object> attributes) {
            String token = extractToken(request);
            if (token == null) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            try {
                Claims claims = jwtService.parse(token);
                String username = claims.getSubject();
                if (username == null) {
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    return false;
                }
                User user = userRepository.findByUsername(username).orElse(null);
                boolean identityMatches = user != null
                        && claims.get("uid") instanceof Number n
                        && user.getId() != null
                        && n.longValue() == user.getId();
                boolean teamActive = user != null && (user.getRole() == Role.SUPER_ADMIN
                        || (user.getTeam() != null && user.getTeam().isActive()));
                Object tv = claims.get("tv");
                long claimedTv = tv instanceof Number num ? num.longValue() : 0L;
                boolean versionCurrent = user != null && claimedTv == user.getTokenVersion();
                if (!identityMatches || user == null || !user.isActive() || !teamActive || !versionCurrent) {
                    log.debug("Rejected chat WS handshake for user={}", username);
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    return false;
                }
                attributes.put(ChatSocketSessionRegistry.ATTR_USER_ID, user.getId());
                return true;
            } catch (Exception e) {
                log.debug("Chat WS handshake failed: {}", e.toString());
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
        }

        @Override
        public void afterHandshake(@NonNull ServerHttpRequest request,
                                   @NonNull ServerHttpResponse response,
                                   @NonNull WebSocketHandler wsHandler,
                                   Exception exception) {
            // no-op
        }

        private String extractToken(ServerHttpRequest request) {
            if (request instanceof ServletServerHttpRequest servlet) {
                String q = servlet.getServletRequest().getParameter("token");
                if (q != null && !q.isBlank()) return q.trim();
                Cookie cookie = WebUtils.getCookie(servlet.getServletRequest(), "dems_auth");
                if (cookie != null && !cookie.getValue().isBlank()) return cookie.getValue();
            }
            return null;
        }
    }
// __PART3__

    /** Processes client frames: SEND / READ / TYPING. Errors go back as ERROR envelopes. */
    @Component
    public static class ChatSocketHandler extends TextWebSocketHandler {

        private static final Logger log = LoggerFactory.getLogger(ChatSocketHandler.class);

        private final ChatSocketSessionRegistry registry;
        private final com.dataentry.service.ChatMessagingService chat;
        private final ObjectMapper mapper;

        public ChatSocketHandler(ChatSocketSessionRegistry registry,
                                 com.dataentry.service.ChatMessagingService chat,
                                 ObjectMapper mapper) {
            this.registry = registry;
            this.chat = chat;
            this.mapper = mapper;
        }

        @Override
        public void afterConnectionEstablished(@NonNull org.springframework.web.socket.WebSocketSession session) {
            registry.register(session);
            Long userId = userIdOf(session);
            if (userId != null) {
                registry.push(userId, new ChatMessagingDtos.WsOut("CONNECTED", null, null, null, null, null, null));
            }
        }

        @Override
        public void afterConnectionClosed(@NonNull org.springframework.web.socket.WebSocketSession session,
                                          @NonNull CloseStatus status) {
            registry.unregister(session);
        }

        @Override
        protected void handleTextMessage(@NonNull org.springframework.web.socket.WebSocketSession session,
                                         @NonNull TextMessage message) {
            Long userId = userIdOf(session);
            if (userId == null) {
                close(session, CloseStatus.POLICY_VIOLATION);
                return;
            }
            ChatMessagingDtos.WsIn in;
            try {
                in = mapper.readValue(message.getPayload(), ChatMessagingDtos.WsIn.class);
            } catch (Exception e) {
                registry.push(userId, ChatMessagingDtos.WsOut.error("Malformed message"));
                return;
            }
            if (in.type() == null || in.conversationId() == null) {
                registry.push(userId, ChatMessagingDtos.WsOut.error("type and conversationId are required"));
                return;
            }
            try {
                switch (in.type().toUpperCase()) {
                    case "SEND" -> chat.sendText(userId, in.conversationId(), in.body(), in.clientMsgId());
                    case "READ" -> chat.markRead(userId, in.conversationId());
                    case "TYPING" -> chat.typing(userId, in.conversationId());
                    default -> registry.push(userId,
                            ChatMessagingDtos.WsOut.error("Unknown type: " + in.type()));
                }
            } catch (org.springframework.web.server.ResponseStatusException e) {
                registry.push(userId, new ChatMessagingDtos.WsOut("ERROR", in.conversationId(), null,
                        null, null, e.getReason() == null ? "Rejected" : e.getReason(), in.clientMsgId()));
            } catch (Exception e) {
                log.warn("Chat frame failed user={} type={}: {}", userId, in.type(), e.toString());
                registry.push(userId, new ChatMessagingDtos.WsOut("ERROR", in.conversationId(), null,
                        null, null, "Message failed", in.clientMsgId()));
            }
        }

        private void close(org.springframework.web.socket.WebSocketSession session, CloseStatus status) {
            try {
                session.close(status);
            } catch (Exception ignored) { }
        }

        private Long userIdOf(org.springframework.web.socket.WebSocketSession session) {
            Object v = session.getAttributes().get(ChatSocketSessionRegistry.ATTR_USER_ID);
            return v instanceof Long l ? l : null;
        }
    }
}
