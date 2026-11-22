package com.nexus.chat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Account-scoped interface preferences.
 *
 * Deliberately narrow: only the settings that should follow a user across
 * machines live here. Device-bound settings (zoom, frame cap, dock badge,
 * encryption) stay on the client.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPreferenceDTO {

    @Pattern(regexp = "^(12|24)$", message = "hourCycle must be 12 or 24")
    private String hourCycle;

    @Min(value = 0, message = "glassIntensity must be at least 0")
    @Max(value = 100, message = "glassIntensity must be at most 100")
    private Integer glassIntensity;

    private Boolean soundEnabled;
}
