package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "message_reactions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"message_id", "user_id", "emoji"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "emoji", nullable = false, length = 32)
    private String emoji;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
