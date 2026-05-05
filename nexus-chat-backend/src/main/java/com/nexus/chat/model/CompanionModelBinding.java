package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "companion_model_bindings",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "role_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanionModelBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(nullable = false, length = 50)
    private String provider = "openai-compatible";

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(length = 500)
    private String endpoint;

    @Column(nullable = false)
    private Boolean enabled = true;
}
