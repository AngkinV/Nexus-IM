package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "message_edit_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageEditHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "editor_user_id", nullable = false)
    private Long editorUserId;

    @Column(name = "previous_content", columnDefinition = "TEXT")
    private String previousContent;

    @Column(name = "new_content", columnDefinition = "TEXT")
    private String newContent;

    @CreationTimestamp
    @Column(name = "edited_at", updatable = false)
    private LocalDateTime editedAt;
}
