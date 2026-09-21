package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One membership row: who is in a group, whether they can manage it, and — via
 * {@link #lastReadAt} — how much of it they have already seen. The watermark is a
 * single instant per member rather than a read flag per message, which is what a
 * durable unread count needs without a row for every (message, member) pair.
 */
@Entity
@Table(name = "chat_group_members", indexes = {
        @Index(name = "ix_chat_group_members_user", columnList = "user_id"),
        @Index(name = "ix_chat_group_members_group", columnList = "group_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatGroupMember {

    public enum GroupRole { ADMIN, MEMBER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private ChatGroup group;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private GroupRole role = GroupRole.MEMBER;

    @Column(name = "joined_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant joinedAt = Instant.now();

    @Column(name = "last_read_at")
    private Instant lastReadAt;

    public boolean isAdmin() {
        return role == GroupRole.ADMIN;
    }
}
