package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A group conversation: any number of members, one or more of whom are {@code ADMIN}
 * (see {@link ChatGroupMember}) and may add, remove, promote or rename. Deliberately
 * separate from {@link ChatConversation} — a 1:1 chat and a group differ enough in
 * shape (participant count, per-member read tracking, admin roles) that sharing one
 * table would mean compromising one to fit the other.
 */
@Entity
@Table(name = "chat_groups", indexes = {
        @Index(name = "ix_chat_groups_team", columnList = "team_id, last_message_at DESC")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null only for a super admin's group that spans more than one team. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @Column(nullable = false, length = 150)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "last_message_at")
    private Instant lastMessageAt;
}
