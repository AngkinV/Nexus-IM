package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Preferences that follow the account rather than the machine.
 *
 * The split matters: things like window zoom, frame-rate cap and the dock badge
 * describe the device the user is sitting at, so they stay in localStorage.
 * These three describe how the user wants the interface to read, and should be
 * the same on every machine they sign in from — so they live here.
 *
 * Modeled on {@link UserPrivacySettings}: one row per user, audited timestamps.
 */
@Entity
@DynamicUpdate // write only the changed columns: concurrent partial updates must not clobber each other
@Table(name = "user_preferences")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", unique = true, nullable = false)
    private Long userId;

    /** '24' or '12'. */
    @Column(name = "hour_cycle", length = 2)
    private String hourCycle = "24";

    /** 0-100; drives the glass blur multiplier. */
    @Column(name = "glass_intensity")
    private Integer glassIntensity = 100;

    /** Whether UI sounds play. */
    @Column(name = "sound_enabled")
    private Boolean soundEnabled = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
