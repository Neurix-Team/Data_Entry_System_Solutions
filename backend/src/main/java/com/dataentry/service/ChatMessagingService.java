package com.dataentry.service;

import com.dataentry.dto.ChatMessagingDtos;
import com.dataentry.model.ChatAttachment;
import com.dataentry.model.ChatConversation;
import com.dataentry.model.ChatMessage;
import com.dataentry.model.Role;
import com.dataentry.model.User;
import com.dataentry.repository.ChatAttachmentRepository;
import com.dataentry.repository.ChatConversationRepository;
import com.dataentry.repository.ChatMessageRepository;
import com.dataentry.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Person-to-person messaging: any USER/ADMIN/SUPER_ADMIN pair. Team isolation holds
 * for USER↔USER/ADMIN pairs; super admins may reach anyone. Delivery is realtime via
 * the /ws/chat registry, with a notification fallback when the recipient is offline.
 */
@Service
public class ChatMessagingService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessagingService.class);

    public static final String NOTIFICATION_TYPE = "CHAT_MESSAGE";
    public static final String NOTIFICATION_REF_TYPE = "CHAT";

    private static final int MAX_FILES_PER_MESSAGE = 5;

    private final ChatConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ChatAttachmentRepository attachments;
    private final UserRepository users;
    private final NotificationService notifications;
    private final ChatSocketSessionRegistry registry;
    private final ChatFilesService files;
    private final ObjectMapper mapper;

    public ChatMessagingService(ChatConversationRepository conversations,
                                ChatMessageRepository messages,
                                ChatAttachmentRepository attachments,
                                UserRepository users,
                                NotificationService notifications,
                                ChatSocketSessionRegistry registry,
                                ChatFilesService files,
                                ObjectMapper mapper) {
        this.conversations = conversations;
        this.messages = messages;
        this.attachments = attachments;
        this.users = users;
        this.notifications = notifications;
        this.registry = registry;
        this.files = files;
        this.mapper = mapper;
    }
// __PART2__

    /* ---------- conversations & contacts ---------- */

    @Transactional(readOnly = true)
    public ChatMessagingDtos.ConversationList listConversations(User me) {
        List<ChatConversation> rows = conversations.findAllForUser(me.getId());
        Map<Long, Long> unreadByConv = new HashMap<>();
        for (Object[] row : messages.countUnreadByConversation(me.getId())) {
            if (row[0] instanceof Number convId && row[1] instanceof Number unread) {
                unreadByConv.put(convId.longValue(), unread.longValue());
            }
        }
        List<ChatMessagingDtos.ConversationItem> items = rows.stream()
                .map(c -> toConversationItem(c, me, unreadByConv.getOrDefault(c.getId(), 0L)))
                .toList();
        long total = items.stream().mapToLong(ChatMessagingDtos.ConversationItem::unreadCount).sum();
        return new ChatMessagingDtos.ConversationList(items, total);
    }

    @Transactional(readOnly = true)
    public ChatMessagingDtos.ContactList contacts(User me) {
        List<User> candidates;
        if (me.getRole() == Role.SUPER_ADMIN) {
            candidates = users.findAll();
        } else {
            Set<User> set = new HashSet<>();
            if (me.getTeam() != null) {
                set.addAll(users.findAllByTeamIdOrderByCreatedAtDesc(me.getTeam().getId()));
            }
            set.addAll(users.findActiveSuperAdminsForChat());
            candidates = new ArrayList<>(set);
        }
        Long myId = me.getId();
        List<ChatMessagingDtos.ContactItem> items = candidates.stream()
                .filter(u -> u.isActive() && !u.getId().equals(myId))
                .map(u -> new ChatMessagingDtos.ContactItem(
                        u.getId(), u.getUsername(),
                        localName(u), u.getDisplayNameEn(), u.getDisplayNameAr(),
                        u.getRole().name(),
                        u.getTeam() == null ? null : u.getTeam().getName(),
                        u.getAvatarUpdatedAt(),
                        pairConversationId(me, u)))
                .sorted(Comparator
                        .comparing((ChatMessagingDtos.ContactItem c) -> roleRank(c.role()))
                        .thenComparing(c -> c.displayName() == null ? "" : c.displayName().toLowerCase()))
                .toList();
        return new ChatMessagingDtos.ContactList(items);
    }

    @Transactional
    public ChatMessagingDtos.ConversationItem startOrGet(User me, Long otherUserId) {
        // native lookup: the tenant filter would hide super admins (team_id IS NULL)
        User other = users.findChatUserById(otherUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!other.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        assertCanMessage(me, other);
        ChatConversation conv = getOrCreate(me, other);
        return toConversationItem(conv, me, 0L);
    }

    private ChatConversation getOrCreate(User me, User other) {
        Long a = Math.min(me.getId(), other.getId());
        Long b = Math.max(me.getId(), other.getId());
        ChatConversation existing = conversations.findByUserAIdAndUserBId(a, b).orElse(null);
        if (existing != null) return existing;
        ChatConversation conv = ChatConversation.builder()
                .team(me.getRole() == Role.SUPER_ADMIN ? other.getTeam() : me.getTeam())
                .userA(users.getReferenceById(a))
                .userB(users.getReferenceById(b))
                .createdAt(Instant.now())
                .build();
        return conversations.save(conv);
    }
// __PART3__

    /* ---------- messages ---------- */

    @Transactional(readOnly = true)
    public ChatMessagingDtos.MessagesPage history(User me, Long conversationId, Long afterId, Integer limitRaw) {
        ChatConversation conv = requireParticipant(me, conversationId);
        int limit = limitRaw == null ? 200 : Math.min(Math.max(limitRaw, 1), 500);
        List<ChatMessage> rows;
        if (afterId != null) {
            rows = messages.findByConversationIdAndIdGreaterThanOrderByIdAsc(conversationId, afterId);
            if (rows.size() > limit) rows = rows.subList(rows.size() - limit, rows.size());
        } else {
            Pageable page = PageRequest.of(0, limit);
            List<ChatMessage> recent = messages.findRecent(conversationId, page);
            java.util.Collections.reverse(recent);
            rows = recent;
        }
        return new ChatMessagingDtos.MessagesPage(conversationId, toMessageItems(rows));
    }

    @Transactional
    public ChatMessagingDtos.MessageItem sendText(Long senderId, Long conversationId, String body, String clientMsgId) {
        User sender = users.findById(senderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return send(sender, conversationId, body, List.of(), clientMsgId);
    }

    @Transactional
    public ChatMessagingDtos.MessageItem sendFiles(User me, Long conversationId, String body,
                                                   List<MultipartFile> files, String clientMsgId) {
        if (files == null || files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No files attached");
        }
        if (files.size() > MAX_FILES_PER_MESSAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Up to " + MAX_FILES_PER_MESSAGE + " files per message");
        }
        return send(me, conversationId, body, files, clientMsgId);
    }

    private ChatMessagingDtos.MessageItem send(User sender, Long conversationId, String body,
                                               List<MultipartFile> files, String clientMsgId) {
        ChatConversation conv = requireParticipant(sender, conversationId);
        String cleanBody = body == null ? null : body.trim();
        boolean hasBody = cleanBody != null && !cleanBody.isEmpty();
        if (!hasBody && (files == null || files.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message is empty");
        }
        if (hasBody && cleanBody.length() > 4000) {
            cleanBody = cleanBody.substring(0, 4000);
        }
        Instant now = Instant.now();
        ChatMessage msg = ChatMessage.builder()
                .conversation(conv)
                .sender(sender)
                .body(cleanBody)
                .createdAt(now)
                .build();
        ChatMessage saved = messages.save(msg);

        List<ChatAttachment> savedAttachments = new ArrayList<>();
        if (files != null && !files.isEmpty()) {
            for (MultipartFile f : files) {
                ChatFilesService.SavedFile stored = this.files.store(conversationId, f, sender.getId());
                ChatAttachment att = ChatAttachment.builder()
                        .message(saved)
                        .originalFilename(stored.originalFilename())
                        .storedName(stored.storedName())
                        .contentType(stored.contentType())
                        .sizeBytes(stored.sizeBytes())
                        .createdAt(now)
                        .build();
                savedAttachments.add(attachments.save(att));
            }
        }
        conv.setLastMessageAt(now);
        conversations.save(conv);

        ChatMessagingDtos.MessageItem item = toMessageItem(saved, savedAttachments);

        // Realtime push to both parties (covers the sender's other tabs/devices too).
        registry.pushToBoth(new ChatSocketSessionRegistry.ChatConversationParticipants(
                        conv.getUserA().getId(), conv.getUserB().getId()),
                new ChatMessagingDtos.WsOut("MESSAGE", conversationId, item, null, sender.getId(), null, clientMsgId));

        // Offline fallback: notification bell catches up on next login/poll.
        User recipient = conv.otherOf(sender);
        if (!registry.isOnline(recipient.getId())) {
            notifyNewMessage(sender, recipient, conv, item);
        }
        return item;
    }

    @Transactional
    public int markRead(Long readerId, Long conversationId) {
        ChatConversation conv = requireParticipantId(readerId, conversationId);
        Instant now = Instant.now();
        int marked = messages.markConversationRead(conversationId, readerId, now);
        if (marked > 0) {
            Long otherId = readerId.equals(conv.getUserA().getId())
                    ? conv.getUserB().getId() : conv.getUserA().getId();
            registry.push(otherId, new ChatMessagingDtos.WsOut(
                    "READ", conversationId, null, readerId, null, null, null));
        }
        return marked;
    }

    public void typing(Long senderId, Long conversationId) {
        ChatConversation conv = conversations.findById(conversationId).orElse(null);
        if (conv == null || !conv.involvesId(senderId)) return;
        Long otherId = senderId.equals(conv.getUserA().getId())
                ? conv.getUserB().getId() : conv.getUserA().getId();
        registry.push(otherId, new ChatMessagingDtos.WsOut(
                "TYPING", conversationId, null, null, senderId, null, null));
    }
// __PART4__

    /* ---------- attachments ---------- */

    @Transactional(readOnly = true)
    public ChatFilesService.DownloadHandle attachmentDownload(User me, Long attachmentId) {
        ChatAttachment att = attachments.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));
        ChatConversation conv = att.getMessage().getConversation();
        if (!conv.involves(me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return files.resolveStored(conv.getId(), att.getStoredName(), att.getOriginalFilename(),
                att.getContentType(), att.getSizeBytes());
    }

    /* ---------- unread badge ---------- */

    @Transactional(readOnly = true)
    public long unreadTotal(User me) {
        return messages.countUnreadByConversation(me.getId()).stream()
                .mapToLong(row -> row[1] instanceof Number n ? n.longValue() : 0L)
                .sum();
    }

    /* ---------- helpers ---------- */

    private ChatConversation requireParticipant(User me, Long conversationId) {
        ChatConversation conv = conversations.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        if (!conv.involves(me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return conv;
    }

    private ChatConversation requireParticipantId(Long userId, Long conversationId) {
        ChatConversation conv = conversations.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        if (!conv.involvesId(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return conv;
    }

    private void assertCanMessage(User me, User other) {
        if (me.getRole() == Role.SUPER_ADMIN) return; // super admin can reach anyone
        boolean sameTeam = me.getTeam() != null && other.getTeam() != null
                && me.getTeam().getId().equals(other.getTeam().getId());
        boolean toSuperAdmin = other.getRole() == Role.SUPER_ADMIN;
        if (!sameTeam && !toSuperAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only chat within your team or with a super admin.");
        }
    }

    private void notifyNewMessage(User sender, User recipient, ChatConversation conv,
                                  ChatMessagingDtos.MessageItem item) {
        try {
            String preview = previewOf(item);
            String name = displayNameOf(sender);
            notifications.emit(recipient, NOTIFICATION_TYPE,
                    "💬 " + name + ": " + preview,
                    NOTIFICATION_REF_TYPE, conv.getId(), null);
        } catch (Exception e) {
            log.debug("Chat notification failed: {}", e.toString());
        }
    }
// __PART5__

    private String previewOf(ChatMessagingDtos.MessageItem item) {
        if (item.body() != null && !item.body().isBlank()) {
            String body = item.body();
            return body.length() > 120 ? body.substring(0, 120) + "…" : body;
        }
        if (item.attachments() != null && !item.attachments().isEmpty()) {
            var first = item.attachments().get(0);
            return switch (first.kind()) {
                case "IMAGE" -> "📷 صورة";
                case "PDF" -> "📄 " + first.filename();
                default -> "📎 " + first.filename();
            };
        }
        return "—";
    }

    private ChatMessagingDtos.ConversationItem toConversationItem(ChatConversation c, User me, long unread) {
        User other = c.otherOf(me);
        String preview = null;
        Instant lastAt = c.getCreatedAt();
        List<ChatMessage> recent = messages.findRecent(c.getId(), PageRequest.of(0, 1));
        if (!recent.isEmpty()) {
            ChatMessage last = recent.get(0);
            preview = previewOf(toMessageItem(last));
            lastAt = last.getCreatedAt();
        }
        return new ChatMessagingDtos.ConversationItem(
                c.getId(),
                other.getId(),
                other.getUsername(),
                displayNameOf(other),
                other.getDisplayNameEn(),
                other.getDisplayNameAr(),
                other.getRole().name(),
                other.getTeam() == null ? null : other.getTeam().getName(),
                other.getAvatarUpdatedAt(),
                preview,
                lastAt,
                unread
        );
    }

    private Long pairConversationId(User me, User other) {
        Long a = Math.min(me.getId(), other.getId());
        Long b = Math.max(me.getId(), other.getId());
        return conversations.findByUserAIdAndUserBId(a, b)
                .map(ChatConversation::getId).orElse(null);
    }

    private List<ChatMessagingDtos.MessageItem> toMessageItems(List<ChatMessage> rows) {
        if (rows.isEmpty()) return List.of();
        Set<Long> ids = rows.stream().map(ChatMessage::getId).collect(Collectors.toSet());
        Map<Long, List<ChatAttachment>> byMessage = attachments.findByMessageIdInOrderByIdAsc(ids).stream()
                .collect(Collectors.groupingBy(a -> a.getMessage().getId()));
        return rows.stream()
                .map(m -> toMessageItem(m, byMessage.getOrDefault(m.getId(), List.of())))
                .toList();
    }

    private ChatMessagingDtos.MessageItem toMessageItem(ChatMessage m) {
        return toMessageItem(m, attachments.findByMessageIdOrderByIdAsc(m.getId()));
    }

    private ChatMessagingDtos.MessageItem toMessageItem(ChatMessage m, List<ChatAttachment> atts) {
        List<ChatMessagingDtos.AttachmentItem> attachmentItems = atts.stream()
                .map(a -> new ChatMessagingDtos.AttachmentItem(
                        a.getId(),
                        a.getOriginalFilename(),
                        a.getContentType(),
                        a.getSizeBytes(),
                        ChatFilesService.kindOf(a.getContentType(), a.getOriginalFilename())))
                .toList();
        return new ChatMessagingDtos.MessageItem(
                m.getId(),
                m.getConversation().getId(),
                m.getSender().getId(),
                displayNameOf(m.getSender()),
                m.getSender().getRole().name(),
                m.getBody(),
                m.getCreatedAt(),
                m.getReadAt(),
                attachmentItems
        );
    }

    private String displayNameOf(User u) {
        if (u.getDisplayName() != null && !u.getDisplayName().isBlank()) return u.getDisplayName();
        return u.getUsername();
    }

    private String localName(User u) {
        return displayNameOf(u);
    }

    private static int roleRank(String role) {
        return switch (role) {
            case "SUPER_ADMIN" -> 0;
            case "ADMIN" -> 1;
            default -> 2;
        };
    }
}
