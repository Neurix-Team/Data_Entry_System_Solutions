package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One message inside a group. {@code kind} tells a plain sent message ({@code TEXT})
 * apart from a system line the server writes itself — "X added Y", "Z left" — so the
 * client can render the second one centered and without a sender bubble.
 */
@Entity
@Table(name = "chat_group_messages", indexes = {
        @Index(name = "ix_chat_group_messages_group_created", columnList = "group_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatGroupMessage {

    public enum Kind { TEXT, SYSTEM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private ChatGroup group;

    /** Null for a system message — nobody "sent" the notice that a member was removed. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "sender_id")
    private User sender;

    @Column(length = 4000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Kind kind = Kind.TEXT;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
