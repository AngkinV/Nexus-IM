package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_members", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "chat_id", "user_id" })
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private MemberRole role = MemberRole.member;

    @Column(name = "is_admin")
    private Boolean isAdmin = false;

    public enum MemberRole {
        owner, admin, member
    }

    @Column(name = "unread_count")
    private Integer unreadCount = 0;

    @CreationTimestamp
    @Column(name = "joined_at", updatable = false)
    private LocalDateTime joinedAt;

}
