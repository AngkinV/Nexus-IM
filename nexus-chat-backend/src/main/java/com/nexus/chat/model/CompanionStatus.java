package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "companion_status",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "role_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanionStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_type", nullable = false)
    private StatusType statusType = StatusType.resting;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum StatusType {
        reading, listening, walking, thinking, resting, chatting, writing, organizing
    }
}
