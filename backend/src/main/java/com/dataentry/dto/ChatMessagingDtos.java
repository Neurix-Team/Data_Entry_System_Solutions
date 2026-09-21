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
            /** Set for a 1:1 message; null for a group message ({@code groupId} is set instead). */
            Long conversationId,
            Long senderId,
            String senderName,
            String senderRole,
            String body,
            Instant createdAt,
            /** 1:1 only — a group has no single read instant, see the member's unread count instead. */
            Instant readAt,
            List<AttachmentItem> attachments,
            /** Set for a group message; null for a 1:1 message. */
            Long groupId,
            /** TEXT | SYSTEM — a system line ("X added Y") has no sender bubble. Null for a 1:1 message. */
            String kind,
            /** The group's name, so a toast/notification can say where it came from without a lookup. */
            String groupName
    ) {
        /** The 1:1 shape — unchanged from before groups existed, group fields null. */
        public static MessageItem direct(Long id, Long conversationId, Long senderId, String senderName,
                                         String senderRole, String body, Instant createdAt, Instant readAt,
                                         List<AttachmentItem> attachments) {
            return new MessageItem(id, conversationId, senderId, senderName, senderRole, body,
                    createdAt, readAt, attachments, null, null, null);
        }

        /** The group shape — {@code conversationId}/{@code readAt} null. */
        public static MessageItem group(Long id, Long groupId, String groupName, Long senderId,
                                        String senderName, String senderRole, String body, Instant createdAt,
                                        List<AttachmentItem> attachments, String kind) {
            return new MessageItem(id, null, senderId, senderName, senderRole, body,
                    createdAt, null, attachments, groupId, kind, groupName);
        }
    }

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
            String clientMsgId,   // optional, echoed back
            /** Exactly one of conversationId/groupId is set — this frame targets a group instead. */
            Long groupId
    ) {}

    public record WsOut(
            String type,          // MESSAGE | READ | TYPING | ERROR | CONNECTED | GROUP_UPDATED
            Long conversationId,
            MessageItem message,
            Long readerId,
            Long fromUserId,
            String error,
            String clientMsgId,
            /** Set on a group frame; null on a 1:1 or connection-level one. */
            Long groupId
    ) {
        public static WsOut error(String msg) { return new WsOut("ERROR", null, null, null, null, msg, null, null); }
    }

    /* ---------- groups ---------- */

    public record CreateGroupRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 150) String name,
            /** At least one other member — a group of one is just a note to self. */
            @jakarta.validation.constraints.NotEmpty List<Long> memberIds
    ) {}

    public record RenameGroupRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 150) String name
    ) {}

    public record AddMembersRequest(
            @jakarta.validation.constraints.NotEmpty List<Long> userIds
    ) {}

    public record GroupMemberItem(
            Long userId,
            String username,
            String displayName,
            String displayNameEn,
            String displayNameAr,
            String role,          // ADMIN | MEMBER
            Instant avatarUpdatedAt,
            Instant joinedAt
    ) {}

    /** One row in the sidebar list — same shape of information a 1:1 ConversationItem gives. */
    public record GroupItem(
            Long id,
            String name,
            int memberCount,
            /** True when the caller is an ADMIN of this group. */
            boolean isAdmin,
            String lastMessagePreview,
            Instant lastMessageAt,
            long unreadCount
    ) {}

    public record GroupList(List<GroupItem> items) {}

    public record GroupDetail(
            Long id,
            String name,
            boolean isAdmin,
            List<GroupMemberItem> members
    ) {}

    public record GroupMessagesPage(
            Long groupId,
            List<MessageItem> messages
    ) {}
}
