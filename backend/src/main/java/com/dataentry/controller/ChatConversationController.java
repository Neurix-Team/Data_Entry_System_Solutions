package com.dataentry.controller;

import com.dataentry.dto.ChatMessagingDtos;
import com.dataentry.model.User;
import com.dataentry.service.ChatFilesService;
import com.dataentry.service.ChatMessagingService;
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
import java.util.List;

/**
 * REST surface of person-to-person chat. History/uploads/downloads live here;
 * realtime delivery rides on /ws/chat.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatConversationController {

    private final ChatMessagingService chat;

    public ChatConversationController(ChatMessagingService chat) {
        this.chat = chat;
    }

    @GetMapping("/conversations")
    public ChatMessagingDtos.ConversationList conversations(@AuthenticationPrincipal User me) {
        return chat.listConversations(me);
    }

    @PostMapping("/conversations")
    public ResponseEntity<ChatMessagingDtos.ConversationItem> start(
            @AuthenticationPrincipal User me,
            @Valid @RequestBody ChatMessagingDtos.StartConversationRequest req) {
        ChatMessagingDtos.ConversationItem item = chat.startOrGet(me, req.userId());
        return ResponseEntity.created(URI.create("/api/chat/conversations/" + item.id())).body(item);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ChatMessagingDtos.MessagesPage messages(@AuthenticationPrincipal User me,
                                                   @PathVariable Long conversationId,
                                                   @RequestParam(required = false) Long afterId,
                                                   @RequestParam(required = false) Integer limit) {
        return chat.history(me, conversationId, afterId, limit);
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ChatMessagingDtos.MessageItem sendText(@AuthenticationPrincipal User me,
                                                  @PathVariable Long conversationId,
                                                  @Valid @RequestBody ChatMessagingDtos.SendMessageRequest req) {
        return chat.sendText(me.getId(), conversationId, req.body(), null);
    }

    @PostMapping(value = "/conversations/{conversationId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatMessagingDtos.MessageItem sendFiles(@AuthenticationPrincipal User me,
                                                   @PathVariable Long conversationId,
                                                   @RequestPart(value = "body", required = false) String body,
                                                   @RequestPart("files") List<MultipartFile> files) {
        return chat.sendFiles(me, conversationId, body, files, null);
    }

    @PostMapping("/conversations/{conversationId}/read")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal User me,
                                         @PathVariable Long conversationId) {
        chat.markRead(me.getId(), conversationId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/conversations/{conversationId}/typing")
    public ResponseEntity<Void> typing(@AuthenticationPrincipal User me,
                                       @PathVariable Long conversationId) {
        chat.typing(me.getId(), conversationId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/contacts")
    public ChatMessagingDtos.ContactList contacts(@AuthenticationPrincipal User me) {
        return chat.contacts(me);
    }

    @GetMapping("/unread")
    public ResponseEntity<Long> unread(@AuthenticationPrincipal User me) {
        return ResponseEntity.ok(chat.unreadTotal(me));
    }

    @GetMapping("/attachments/{attachmentId}")
    public ResponseEntity<Resource> attachment(@AuthenticationPrincipal User me,
                                               @PathVariable Long attachmentId) {
        ChatFilesService.DownloadHandle h = chat.attachmentDownload(me, attachmentId);
        String safeName = h.filename() == null ? "file" : h.filename().replace("\"", "");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        h.contentType() == null ? "application/octet-stream" : h.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeName + "\"; filename*=UTF-8''"
                                + java.net.URLEncoder.encode(safeName, java.nio.charset.StandardCharsets.UTF_8))
                .contentLength(h.size())
                .body(h.resource());
    }
}
