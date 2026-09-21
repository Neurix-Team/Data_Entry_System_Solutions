package com.dataentry.service;

import com.dataentry.dto.ChatMessagingDtos;
import com.dataentry.model.ChatGroup;
import com.dataentry.model.ChatGroupAttachment;
import com.dataentry.model.ChatGroupMember;
import com.dataentry.model.ChatGroupMessage;
import com.dataentry.model.Role;
import com.dataentry.model.User;
import com.dataentry.repository.ChatGroupAttachmentRepository;
import com.dataentry.repository.ChatGroupMemberRepository;
import com.dataentry.repository.ChatGroupMessageRepository;
import com.dataentry.repository.ChatGroupRepository;
import com.dataentry.repository.UserRepository;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Group messaging: any number of members, one or more {@code ADMIN}s who can add or
 * remove members, promote or demote, and rename. Deliberately its own set of tables and
 * this own service rather than folded into {@link ChatMessagingService} — see the V12
 * migration's header comment for why — but delivery rides the same /ws/chat transport
 * and {@link ChatSocketSessionRegistry}, and file storage reuses {@link ChatFilesService}
 * under a namespace ({@code group-<id>}) that can never collide with a 1:1 conversation's.
 */
@Service
public class ChatGroupService {

    private static final Logger log = LoggerFactory.getLogger(ChatGroupService.class);

    public static final String NOTIFICATION_TYPE = "CHAT_GROUP_MESSAGE";
    public static final String NOTIFICATION_REF_TYPE = "CHAT_GROUP";

    private static final int MAX_FILES_PER_MESSAGE = 5;
    private static final int MAX_MEMBERS = 200;

    private final ChatGroupRepository groups;
    private final ChatGroupMemberRepository members;
    private final ChatGroupMessageRepository messages;
    private final ChatGroupAttachmentRepository attachments;
    private final UserRepository users;
    private final NotificationService notifications;
    private final ChatSocketSessionRegistry registry;
    private final ChatFilesService files;
    private final AuditService audit;

    public ChatGroupService(ChatGroupRepository groups,
                            ChatGroupMemberRepository members,
                            ChatGroupMessageRepository messages,
                            ChatGroupAttachmentRepository attachments,
                            UserRepository users,
                            NotificationService notifications,
                            ChatSocketSessionRegistry registry,
                            ChatFilesService files,
                            AuditService audit) {
        this.groups = groups;
        this.members = members;
        this.messages = messages;
        this.attachments = attachments;
        this.users = users;
        this.notifications = notifications;
        this.registry = registry;
        this.files = files;
        this.audit = audit;
    }

    /* ---------- listing ---------- */

    @Transactional(readOnly = true)
    public ChatMessagingDtos.GroupList listGroups(User me) {
        List<ChatGroupMember> mine = members.findAllByUserId(me.getId());
        List<ChatMessagingDtos.GroupItem> items = mine.stream()
                .map(m -> toGroupItem(m.getGroup(), me, m))
                .sorted(Comparator.comparing(
                        (ChatMessagingDtos.GroupItem g) -> g.lastMessageAt() == null ? Instant.EPOCH : g.lastMessageAt())
                        .reversed())
                .toList();
        return new ChatMessagingDtos.GroupList(items);
    }

    @Transactional(readOnly = true)
    public ChatMessagingDtos.GroupDetail detail(User me, Long groupId) {
        ChatGroupMember mine = requireMember(me.getId(), groupId);
        List<ChatGroupMember> rows = members.findAllByGroupIdOrderByRoleAscJoinedAtAsc(groupId);
        List<ChatMessagingDtos.GroupMemberItem> memberItems = rows.stream()
                .map(this::toMemberItem)
                .toList();
        return new ChatMessagingDtos.GroupDetail(groupId, mine.getGroup().getName(), mine.isAdmin(), memberItems);
    }
// __PART2__

    /* ---------- create / manage ---------- */

    @Transactional
    public ChatMessagingDtos.GroupItem create(User me, ChatMessagingDtos.CreateGroupRequest req) {
        String name = req.name().trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name is required");
        }
        Set<Long> memberIds = new LinkedHashSet<>(req.memberIds());
        memberIds.remove(me.getId()); // the creator is added as ADMIN below regardless
        if (memberIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Pick at least one other member");
        }
        if (memberIds.size() + 1 > MAX_MEMBERS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A group can have at most " + MAX_MEMBERS + " members");
        }
        List<User> candidates = resolveMessageable(me, memberIds);

        ChatGroup group = groups.save(ChatGroup.builder()
                .team(me.getRole() == Role.SUPER_ADMIN ? null : me.getTeam())
                .name(name.length() > 150 ? name.substring(0, 150) : name)
                .createdBy(me)
                .createdAt(Instant.now())
                .build());

        ChatGroupMember admin = members.save(ChatGroupMember.builder()
                .group(group).user(me)
                .role(ChatGroupMember.GroupRole.ADMIN)
                .joinedAt(Instant.now())
                .lastReadAt(Instant.now())
                .build());
        for (User u : candidates) {
            members.save(ChatGroupMember.builder()
                    .group(group).user(u)
                    .role(ChatGroupMember.GroupRole.MEMBER)
                    .joinedAt(Instant.now())
                    .build());
        }

        String names = candidates.stream().map(this::displayNameOf).collect(Collectors.joining(", "));
        systemMessage(group, displayNameOf(me) + " created the group and added " + names + ".");
        audit.record(AuditService.Action.CREATE, AuditService.EntityType.USER, group.getId(),
                "chatGroupCreated members=" + (candidates.size() + 1));

        pushGroupUpdated(group.getId(), memberIdsOf(group.getId()));
        return toGroupItem(group, me, admin);
    }

    @Transactional
    public void rename(User me, Long groupId, ChatMessagingDtos.RenameGroupRequest req) {
        ChatGroupMember mine = requireAdmin(me.getId(), groupId);
        String name = req.name().trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name is required");
        }
        ChatGroup group = mine.getGroup();
        group.setName(name.length() > 150 ? name.substring(0, 150) : name);
        groups.save(group);
        systemMessage(group, displayNameOf(me) + " renamed the group to \"" + group.getName() + "\".");
        pushGroupUpdated(groupId, memberIdsOf(groupId));
    }

    @Transactional
    public void addMembers(User me, Long groupId, ChatMessagingDtos.AddMembersRequest req) {
        ChatGroupMember mine = requireAdmin(me.getId(), groupId);
        ChatGroup group = mine.getGroup();
        Set<Long> requested = new LinkedHashSet<>(req.userIds());
        Set<Long> already = members.findAllByGroupIdOrderByRoleAscJoinedAtAsc(groupId).stream()
                .map(m -> m.getUser().getId()).collect(Collectors.toSet());
        requested.removeAll(already);
        if (requested.isEmpty()) return;
        if (already.size() + requested.size() > MAX_MEMBERS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A group can have at most " + MAX_MEMBERS + " members");
        }
        List<User> candidates = resolveMessageable(me, requested);
        for (User u : candidates) {
            members.save(ChatGroupMember.builder()
                    .group(group).user(u)
                    .role(ChatGroupMember.GroupRole.MEMBER)
                    .joinedAt(Instant.now())
                    .build());
        }
        String names = candidates.stream().map(this::displayNameOf).collect(Collectors.joining(", "));
        systemMessage(group, displayNameOf(me) + " added " + names + ".");
        pushGroupUpdated(groupId, memberIdsOf(groupId));
    }

    /**
     * Removes a member. An admin may remove anyone else; anyone may remove themselves
     * (leave). If that empties the group's admin seat while members remain, the
     * longest-tenured remaining member is promoted — a group with people in it must
     * never end up with nobody able to manage it.
     */
    @Transactional
    public void removeMember(User me, Long groupId, Long targetUserId) {
        ChatGroupMember mine = requireMember(me.getId(), groupId);
        boolean leavingSelf = targetUserId.equals(me.getId());
        if (!leavingSelf && !mine.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a group admin can remove a member");
        }
        ChatGroupMember target = members.findByGroupIdAndUserId(groupId, targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a member of this group"));
        ChatGroup group = target.getGroup();
        String targetName = displayNameOf(target.getUser());
        members.delete(target);

        List<ChatGroupMember> remaining = members.findAllByGroupIdOrderByRoleAscJoinedAtAsc(groupId);
        if (!remaining.isEmpty() && remaining.stream().noneMatch(ChatGroupMember::isAdmin)) {
            ChatGroupMember promoted = remaining.get(0);
            promoted.setRole(ChatGroupMember.GroupRole.ADMIN);
            members.save(promoted);
            systemMessage(group, displayNameOf(promoted.getUser()) + " is now an admin (the group needs one).");
        }

        systemMessage(group, leavingSelf
                ? targetName + " left the group."
                : displayNameOf(me) + " removed " + targetName + ".");
        pushGroupUpdated(groupId, memberIdsOf(groupId));
        registry.push(targetUserId, new ChatMessagingDtos.WsOut(
                "GROUP_REMOVED", null, null, null, me.getId(), null, null, groupId));
    }

    @Transactional
    public void setAdmin(User me, Long groupId, Long targetUserId, boolean admin) {
        requireAdmin(me.getId(), groupId);
        ChatGroupMember target = members.findByGroupIdAndUserId(groupId, targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a member of this group"));
        if (!admin && target.isAdmin()
                && members.countByGroupIdAndRole(groupId, ChatGroupMember.GroupRole.ADMIN) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A group needs at least one admin — promote someone else first.");
        }
        target.setRole(admin ? ChatGroupMember.GroupRole.ADMIN : ChatGroupMember.GroupRole.MEMBER);
        members.save(target);
        ChatGroup group = target.getGroup();
        systemMessage(group, displayNameOf(me) + (admin ? " made " : " removed ") + displayNameOf(target.getUser())
                + (admin ? " an admin." : " as an admin."));
        pushGroupUpdated(groupId, memberIdsOf(groupId));
    }
// __PART3__

    /* ---------- messages ---------- */

    @Transactional(readOnly = true)
    public ChatMessagingDtos.GroupMessagesPage history(User me, Long groupId, Long afterId, Integer limitRaw) {
        requireMember(me.getId(), groupId);
        int limit = limitRaw == null ? 200 : Math.min(Math.max(limitRaw, 1), 500);
        List<ChatGroupMessage> rows;
        if (afterId != null) {
            rows = messages.findByGroupIdAndIdGreaterThanOrderByIdAsc(groupId, afterId);
            if (rows.size() > limit) rows = rows.subList(rows.size() - limit, rows.size());
        } else {
            Pageable page = PageRequest.of(0, limit);
            List<ChatGroupMessage> recent = messages.findRecent(groupId, page);
            java.util.Collections.reverse(recent);
            rows = recent;
        }
        return new ChatMessagingDtos.GroupMessagesPage(groupId, toMessageItems(rows));
    }

    @Transactional
    public ChatMessagingDtos.MessageItem sendText(Long senderId, Long groupId, String body, String clientMsgId) {
        User sender = users.findById(senderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return send(sender, groupId, body, List.of(), clientMsgId);
    }

    @Transactional
    public ChatMessagingDtos.MessageItem sendFiles(User me, Long groupId, String body,
                                                   List<MultipartFile> uploads, String clientMsgId) {
        if (uploads == null || uploads.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No files attached");
        }
        if (uploads.size() > MAX_FILES_PER_MESSAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Up to " + MAX_FILES_PER_MESSAGE + " files per message");
        }
        return send(me, groupId, body, uploads, clientMsgId);
    }

    private ChatMessagingDtos.MessageItem send(User sender, Long groupId, String body,
                                               List<MultipartFile> uploads, String clientMsgId) {
        ChatGroupMember mine = requireMember(sender.getId(), groupId);
        ChatGroup group = mine.getGroup();
        String cleanBody = body == null ? null : body.trim();
        boolean hasBody = cleanBody != null && !cleanBody.isEmpty();
        if (!hasBody && (uploads == null || uploads.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message is empty");
        }
        if (hasBody && cleanBody.length() > 4000) {
            cleanBody = cleanBody.substring(0, 4000);
        }
        Instant now = Instant.now();
        ChatGroupMessage msg = messages.save(ChatGroupMessage.builder()
                .group(group).sender(sender).body(cleanBody)
                .kind(ChatGroupMessage.Kind.TEXT).createdAt(now)
                .build());

        List<ChatGroupAttachment> savedAttachments = new ArrayList<>();
        if (uploads != null && !uploads.isEmpty()) {
            for (MultipartFile f : uploads) {
                ChatFilesService.SavedFile stored = files.storeForGroup(groupId, f, sender.getId());
                savedAttachments.add(attachments.save(ChatGroupAttachment.builder()
                        .message(msg)
                        .originalFilename(stored.originalFilename())
                        .storedName(stored.storedName())
                        .contentType(stored.contentType())
                        .sizeBytes(stored.sizeBytes())
                        .createdAt(now)
                        .build()));
            }
        }
        group.setLastMessageAt(now);
        groups.save(group);
        // Sending counts as having read up to this instant — mirrors the 1:1 flow, where
        // the sender's own message never shows up as something they owe themselves a read on.
        mine.setLastReadAt(now);
        members.save(mine);

        ChatMessagingDtos.MessageItem item = toMessageItem(msg, savedAttachments);
        List<Long> targets = memberIdsOf(groupId);
        registry.pushToMany(targets, new ChatMessagingDtos.WsOut(
                "MESSAGE", null, item, null, sender.getId(), null, clientMsgId, groupId));

        notifyOfflineMembers(sender, group, targets, item);
        return item;
    }

    @Transactional
    public int markRead(Long readerId, Long groupId) {
        ChatGroupMember mine = requireMember(readerId, groupId);
        mine.setLastReadAt(Instant.now());
        members.save(mine);
        return 1;
    }

    public void typing(Long senderId, Long groupId) {
        ChatGroupMember mine = members.findByGroupIdAndUserId(groupId, senderId).orElse(null);
        if (mine == null) return;
        List<Long> others = memberIdsOf(groupId).stream()
                .filter(id -> !id.equals(senderId)).toList();
        registry.pushToMany(others, new ChatMessagingDtos.WsOut(
                "TYPING", null, null, null, senderId, null, null, groupId));
    }
// __PART4__

    /* ---------- attachments ---------- */

    @Transactional(readOnly = true)
    public ChatFilesService.DownloadHandle attachmentDownload(User me, Long attachmentId) {
        ChatGroupAttachment att = attachments.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));
        Long groupId = att.getMessage().getGroup().getId();
        requireMember(me.getId(), groupId);
        return files.resolveStoredForGroup(groupId, att.getStoredName(), att.getOriginalFilename(),
                att.getContentType(), att.getSizeBytes());
    }

    /* ---------- unread badge ---------- */

    @Transactional(readOnly = true)
    public long unreadTotal(User me) {
        return members.findAllByUserId(me.getId()).stream()
                .mapToLong(m -> messages.countUnread(m.getGroup().getId(), me.getId(),
                        m.getLastReadAt() == null ? Instant.EPOCH : m.getLastReadAt()))
                .sum();
    }

    /* ---------- helpers ---------- */

    private ChatGroupMember requireMember(Long userId, Long groupId) {
        return members.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this group"));
    }

    private ChatGroupMember requireAdmin(Long userId, Long groupId) {
        ChatGroupMember m = requireMember(userId, groupId);
        if (!m.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a group admin can do that");
        }
        return m;
    }

    /**
     * The same reachability rule 1:1 chat uses (own team, or a super admin on either
     * side), applied to a whole candidate list at once for group creation/invites.
     */
    private List<User> resolveMessageable(User me, Set<Long> candidateIds) {
        List<User> found = users.findAllById(candidateIds);
        Map<Long, User> byId = found.stream().collect(Collectors.toMap(User::getId, u -> u));
        List<User> ok = new ArrayList<>();
        for (Long id : candidateIds) {
            User u = byId.get(id);
            if (u == null || !u.isActive()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + id);
            }
            boolean sameTeam = me.getRole() == Role.SUPER_ADMIN
                    || (me.getTeam() != null && u.getTeam() != null
                        && me.getTeam().getId().equals(u.getTeam().getId()));
            boolean superAdminEitherSide = me.getRole() == Role.SUPER_ADMIN || u.getRole() == Role.SUPER_ADMIN;
            if (!sameTeam && !superAdminEitherSide) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You can only add people from your team, or a super admin.");
            }
            ok.add(u);
        }
        return ok;
    }

    private List<Long> memberIdsOf(Long groupId) {
        return members.findAllByGroupIdOrderByRoleAscJoinedAtAsc(groupId).stream()
                .map(m -> m.getUser().getId()).toList();
    }

    private void systemMessage(ChatGroup group, String text) {
        Instant now = Instant.now();
        ChatGroupMessage saved = messages.save(ChatGroupMessage.builder()
                .group(group).sender(null).body(text)
                .kind(ChatGroupMessage.Kind.SYSTEM).createdAt(now)
                .build());
        group.setLastMessageAt(now);
        groups.save(group);
        // The saved row's id, not null: the client keys and de-duplicates rows by it.
        ChatMessagingDtos.MessageItem item = ChatMessagingDtos.MessageItem.group(
                saved.getId(), group.getId(), group.getName(), null, null, null, text, now, List.of(), "SYSTEM");
        registry.pushToMany(memberIdsOf(group.getId()), new ChatMessagingDtos.WsOut(
                "MESSAGE", null, item, null, null, null, null, group.getId()));
    }

    private void pushGroupUpdated(Long groupId, List<Long> memberIds) {
        registry.pushToMany(memberIds, new ChatMessagingDtos.WsOut(
                "GROUP_UPDATED", null, null, null, null, null, null, groupId));
    }

    private void notifyOfflineMembers(User sender, ChatGroup group, List<Long> memberIds,
                                      ChatMessagingDtos.MessageItem item) {
        String preview = ChatMessagingService.previewOfShared(item.body(), item.attachments());
        String senderName = displayNameOf(sender);
        for (Long id : memberIds) {
            if (id.equals(sender.getId()) || registry.isOnline(id)) continue;
            User recipient = users.findById(id).orElse(null);
            if (recipient == null) continue;
            try {
                notifications.emit(recipient, NOTIFICATION_TYPE,
                        "👥 " + group.getName() + " — " + senderName + ": " + preview,
                        NOTIFICATION_REF_TYPE, group.getId(), null);
            } catch (Exception e) {
                log.debug("Group chat notification failed: {}", e.toString());
            }
        }
    }

    private ChatMessagingDtos.GroupItem toGroupItem(ChatGroup group, User me, ChatGroupMember mine) {
        List<ChatGroupMessage> recent = messages.findRecent(group.getId(), PageRequest.of(0, 1));
        String preview = null;
        Instant lastAt = group.getCreatedAt();
        if (!recent.isEmpty()) {
            ChatGroupMessage last = recent.get(0);
            preview = last.getKind() == ChatGroupMessage.Kind.SYSTEM
                    ? last.getBody()
                    : ChatMessagingService.previewOfShared(last.getBody(), attachmentItemsOf(last));
            lastAt = last.getCreatedAt();
        }
        long unread = messages.countUnread(group.getId(), me.getId(),
                mine.getLastReadAt() == null ? Instant.EPOCH : mine.getLastReadAt());
        long memberCount = members.countByGroupId(group.getId());
        return new ChatMessagingDtos.GroupItem(
                group.getId(), group.getName(), (int) memberCount, mine.isAdmin(),
                preview, lastAt, unread);
    }

    private List<ChatMessagingDtos.AttachmentItem> attachmentItemsOf(ChatGroupMessage m) {
        return attachments.findByMessageIdOrderByIdAsc(m.getId()).stream()
                .map(a -> new ChatMessagingDtos.AttachmentItem(a.getId(), a.getOriginalFilename(),
                        a.getContentType(), a.getSizeBytes(),
                        ChatFilesService.kindOf(a.getContentType(), a.getOriginalFilename())))
                .toList();
    }

    private ChatMessagingDtos.GroupMemberItem toMemberItem(ChatGroupMember m) {
        User u = m.getUser();
        return new ChatMessagingDtos.GroupMemberItem(
                u.getId(), u.getUsername(), displayNameOf(u), u.getDisplayNameEn(), u.getDisplayNameAr(),
                m.getRole().name(), u.getAvatarUpdatedAt(), m.getJoinedAt());
    }

    private List<ChatMessagingDtos.MessageItem> toMessageItems(List<ChatGroupMessage> rows) {
        if (rows.isEmpty()) return List.of();
        Set<Long> ids = rows.stream().map(ChatGroupMessage::getId).collect(Collectors.toSet());
        Map<Long, List<ChatGroupAttachment>> byMessage = attachments.findByMessageIdInOrderByIdAsc(ids).stream()
                .collect(Collectors.groupingBy(a -> a.getMessage().getId()));
        return rows.stream()
                .map(m -> toMessageItem(m, byMessage.getOrDefault(m.getId(), List.of())))
                .toList();
    }

    private ChatMessagingDtos.MessageItem toMessageItem(ChatGroupMessage m, List<ChatGroupAttachment> atts) {
        List<ChatMessagingDtos.AttachmentItem> attachmentItems = atts.stream()
                .map(a -> new ChatMessagingDtos.AttachmentItem(
                        a.getId(), a.getOriginalFilename(), a.getContentType(), a.getSizeBytes(),
                        ChatFilesService.kindOf(a.getContentType(), a.getOriginalFilename())))
                .toList();
        return ChatMessagingDtos.MessageItem.group(
                m.getId(), m.getGroup().getId(), m.getGroup().getName(),
                m.getSender() == null ? null : m.getSender().getId(),
                m.getSender() == null ? null : displayNameOf(m.getSender()),
                m.getSender() == null ? null : m.getSender().getRole().name(),
                m.getBody(), m.getCreatedAt(), attachmentItems, m.getKind().name());
    }

    private String displayNameOf(User u) {
        if (u.getDisplayName() != null && !u.getDisplayName().isBlank()) return u.getDisplayName();
        return u.getUsername();
    }
}
