package com.dataentry.dto;

import java.time.Instant;
import java.util.List;

/** DTOs for the person-to-person messaging feature (REST + /ws/chat pushes). */
public class ChatMessagingDtos {

    public record AttachmentItem(
            Long id,
            String filename,
            String contentType,
            long sizeBytes,
            String kind // IMAGE | PDF | DOC
    ) {}

    public record MessageItem(
            Long id,
            Long conversationId,
            Long senderId,
            String senderName,
            String senderRole,
            String body,
            Instant createdAt,
            Instant readAt,
            List<AttachmentItem> attachments
    ) {}

    public record ConversationItem(
            Long id,
            Long otherUserId,
            String otherUsername,
            String otherName,
            String otherNameEn,
            String otherNameAr,
            String otherRole,
            String otherTeam,
            Instant otherAvatarUpdatedAt,
            String lastMessagePreview,
            Instant lastMessageAt,
            long unreadCount
    ) {}

    public record ConversationList(
            List<ConversationItem> items,
            long totalUnread
    ) {}

    public record ContactItem(
            Long id,
            String username,
            String displayName,
            String displayNameEn,
            String displayNameAr,
            String role,
            String team,
            Instant avatarUpdatedAt,
            Long conversationId // existing conversation id, or null
    ) {}

    public record ContactList(List<ContactItem> items) {}

    public record StartConversationRequest(
            Long userId
    ) {}

    public record SendMessageRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 4000) String body
    ) {}

    public record MessagesPage(
            Long conversationId,
            List<MessageItem> messages
    ) {}

    /* ---------- WebSocket envelope ---------- */

    public record WsIn(
            String type,          // SEND | READ | TYPING
            Long conversationId,
            String body,
            String clientMsgId    // optional, echoed back
    ) {}

    public record WsOut(
            String type,          // MESSAGE | READ | TYPING | ERROR | CONNECTED
            Long conversationId,
            MessageItem message,
            Long readerId,
            Long fromUserId,
            String error,
            String clientMsgId
    ) {
        public static WsOut error(String msg) { return new WsOut("ERROR", null, null, null, null, msg, null); }
    }
}
