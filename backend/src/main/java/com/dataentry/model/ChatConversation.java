package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "chat_conversations",
       uniqueConstraints = @UniqueConstraint(name = "chat_conversations_pair_uk",
                                             columnNames = {"user_a_id", "user_b_id"}),
       indexes = {
               @Index(name = "ix_chat_conv_a_last", columnList = "user_a_id, last_message_at DESC"),
               @Index(name = "ix_chat_conv_b_last", columnList = "user_b_id, last_message_at DESC")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatConversation {

    /** Canonical ordering guarantees a single row per pair (user_a_id &lt; user_b_id). */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_a_id", nullable = false)
    private User userA;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_b_id", nullable = false)
    private User userB;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    public boolean involves(User user) {
        return user != null
                && (user.getId().equals(userA.getId()) || user.getId().equals(userB.getId()));
    }

    public boolean involvesId(Long userId) {
        return userId != null
                && (userId.equals(userA.getId()) || userId.equals(userB.getId()));
    }

    public User otherOf(User user) {
        return user.getId().equals(userA.getId()) ? userB : userA;
    }
}
