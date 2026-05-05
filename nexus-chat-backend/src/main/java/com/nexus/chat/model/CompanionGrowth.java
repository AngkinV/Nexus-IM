package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "companion_growths",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "role_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanionGrowth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(nullable = false)
    private Integer intimacy = 0;

    @Column(nullable = false)
    private Integer trust = 0;

    @Column(nullable = false)
    private Integer stability = 80;

    @Column(name = "co_growth", nullable = false)
    private Integer coGrowth = 0;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
