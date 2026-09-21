package com.dataentry.controller;

import com.dataentry.dto.ChatMessagingDtos;
import com.dataentry.model.User;
import com.dataentry.service.ChatFilesService;
import com.dataentry.service.ChatGroupService;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * REST surface of group chat. History/membership/uploads/downloads live here; realtime
 * delivery rides on the same /ws/chat endpoint as 1:1 chat, distinguished by carrying a
 * {@code groupId} instead of a {@code conversationId}.
 */
@RestController
@RequestMapping("/api/chat/groups")
public class ChatGroupController {

    private final ChatGroupService groups;

    public ChatGroupController(ChatGroupService groups) {
        this.groups = groups;
    }

    @GetMapping
    public ChatMessagingDtos.GroupList list(@AuthenticationPrincipal User me) {
        return groups.listGroups(me);
    }

    @PostMapping
    public ResponseEntity<ChatMessagingDtos.GroupItem> create(
            @AuthenticationPrincipal User me,
            @Valid @RequestBody ChatMessagingDtos.CreateGroupRequest req) {
        ChatMessagingDtos.GroupItem item = groups.create(me, req);
        return ResponseEntity.created(URI.create("/api/chat/groups/" + item.id())).body(item);
    }

    @GetMapping("/{groupId}")
    public ChatMessagingDtos.GroupDetail detail(@AuthenticationPrincipal User me, @PathVariable Long groupId) {
        return groups.detail(me, groupId);
    }

    @PatchMapping("/{groupId}")
    public ResponseEntity<Void> rename(@AuthenticationPrincipal User me, @PathVariable Long groupId,
                                       @Valid @RequestBody ChatMessagingDtos.RenameGroupRequest req) {
        groups.rename(me, groupId, req);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{groupId}/members")
    public ResponseEntity<Void> addMembers(@AuthenticationPrincipal User me, @PathVariable Long groupId,
                                           @Valid @RequestBody ChatMessagingDtos.AddMembersRequest req) {
        groups.addMembers(me, groupId, req);
        return ResponseEntity.noContent().build();
    }

    /** A member removing themselves is how "leave group" works — same endpoint, own id. */
    @DeleteMapping("/{groupId}/members/{userId}")
    public ResponseEntity<Void> removeMember(@AuthenticationPrincipal User me, @PathVariable Long groupId,
                                             @PathVariable Long userId) {
        groups.removeMember(me, groupId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{groupId}/members/{userId}/admin")
    public ResponseEntity<Void> promote(@AuthenticationPrincipal User me, @PathVariable Long groupId,
                                        @PathVariable Long userId) {
        groups.setAdmin(me, groupId, userId, true);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{groupId}/members/{userId}/admin")
    public ResponseEntity<Void> demote(@AuthenticationPrincipal User me, @PathVariable Long groupId,
                                       @PathVariable Long userId) {
        groups.setAdmin(me, groupId, userId, false);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{groupId}/messages")
    public ChatMessagingDtos.GroupMessagesPage messages(@AuthenticationPrincipal User me,
                                                        @PathVariable Long groupId,
                                                        @RequestParam(required = false) Long afterId,
                                                        @RequestParam(required = false) Integer limit) {
        return groups.history(me, groupId, afterId, limit);
    }

    @PostMapping("/{groupId}/messages")
    public ChatMessagingDtos.MessageItem sendText(@AuthenticationPrincipal User me, @PathVariable Long groupId,
                                                  @Valid @RequestBody ChatMessagingDtos.SendMessageRequest req) {
        return groups.sendText(me.getId(), groupId, req.body(), null);
    }

    @PostMapping(value = "/{groupId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatMessagingDtos.MessageItem sendFiles(@AuthenticationPrincipal User me, @PathVariable Long groupId,
                                                   @RequestPart(value = "body", required = false) String body,
                                                   @RequestPart("files") List<MultipartFile> files) {
        return groups.sendFiles(me, groupId, body, files, null);
    }

    @PostMapping("/{groupId}/read")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal User me, @PathVariable Long groupId) {
        groups.markRead(me.getId(), groupId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{groupId}/typing")
    public ResponseEntity<Void> typing(@AuthenticationPrincipal User me, @PathVariable Long groupId) {
        groups.typing(me.getId(), groupId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/attachments/{attachmentId}")
    public ResponseEntity<Resource> attachment(@AuthenticationPrincipal User me,
                                               @PathVariable Long attachmentId) {
        ChatFilesService.DownloadHandle h = groups.attachmentDownload(me, attachmentId);
        String safeName = h.filename() == null ? "file" : h.filename().replace("\"", "");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        h.contentType() == null ? "application/octet-stream" : h.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeName + "\"; filename*=UTF-8''"
                                + java.net.URLEncoder.encode(safeName, StandardCharsets.UTF_8))
                .contentLength(h.size())
                .body(h.resource());
    }
}
