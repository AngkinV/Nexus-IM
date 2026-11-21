package com.nexus.chat.service;

import com.nexus.chat.dto.UserPreferenceDTO;
import com.nexus.chat.model.UserPreference;
import com.nexus.chat.repository.UserPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account-scoped preferences: read with defaults, write with merge semantics.
 *
 * Partial updates matter here — the client sends whichever field the user just
 * changed, and a null means "leave it alone", not "set it to null". That keeps
 * two windows open on the same account from clobbering each other.
 */
@Service
@RequiredArgsConstructor
public class UserPreferenceService {

    private final UserPreferenceRepository repository;

    /** Read the stored preferences, creating a default row on first access. */
    @Transactional
    public UserPreferenceDTO getPreferences(Long userId) {
        UserPreference entity = repository
                .findByUserId(userId)
                .orElseGet(() -> repository.save(defaultsFor(userId)));
        return toDto(entity);
    }

    /** Merge the supplied fields into the stored row. */
    @Transactional
    public UserPreferenceDTO updatePreferences(Long userId, UserPreferenceDTO request) {
        UserPreference entity = repository
                .findByUserId(userId)
                .orElseGet(() -> defaultsFor(userId));

        if (request.getHourCycle() != null) {
            entity.setHourCycle(request.getHourCycle());
        }
        if (request.getGlassIntensity() != null) {
            entity.setGlassIntensity(request.getGlassIntensity());
        }
        if (request.getSoundEnabled() != null) {
            entity.setSoundEnabled(request.getSoundEnabled());
        }

        return toDto(repository.save(entity));
    }

    private UserPreference defaultsFor(Long userId) {
        UserPreference entity = new UserPreference();
        entity.setUserId(userId);
        entity.setHourCycle("24");
        entity.setGlassIntensity(100);
        entity.setSoundEnabled(true);
        return entity;
    }

    private UserPreferenceDTO toDto(UserPreference entity) {
        return new UserPreferenceDTO(
                entity.getHourCycle(),
                entity.getGlassIntensity(),
                entity.getSoundEnabled());
    }
}
