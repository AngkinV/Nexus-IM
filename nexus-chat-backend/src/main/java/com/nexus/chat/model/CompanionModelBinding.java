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

    // ── Inference dynamics ──────────────────────────────────────────────────
    // The tavern's own sampling knobs, kept per role so two characters can run
    // at different temperatures. Null means "use the default for that field".

    @Column(name = "temperature")
    private Double temperature;

    /** How hard the reply is held to the character's persona, 50–100. */
    @Column(name = "persona_lock")
    private Integer personaLock;

    /** Token budget for the inner monologue, 256–4096. */
    @Column(name = "reasoning_budget")
    private Integer reasoningBudget;

    /** Context window in tokens: 32000 / 64000 / 128000. */
    @Column(name = "context_horizon")
    private Integer contextHorizon;

    @Column(name = "stream_enabled")
    private Boolean streamEnabled;
}
