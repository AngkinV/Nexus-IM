package com.nexus.chat.service;

import com.nexus.chat.dto.UserPreferenceDTO;
import com.nexus.chat.model.UserPreference;
import com.nexus.chat.repository.UserPreferenceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Unit tests for account-scoped preference merge semantics. */
class UserPreferenceServiceTest {

    private final UserPreferenceRepository repository = mock(UserPreferenceRepository.class);
    private final UserPreferenceService service = new UserPreferenceService(repository);

    @Test
    void getPreferencesCreatesDefaultsOnFirstAccess() {
        when(repository.findByUserId(7L)).thenReturn(Optional.empty());
        when(repository.save(any(UserPreference.class))).thenAnswer(inv -> inv.getArgument(0));

        UserPreferenceDTO dto = service.getPreferences(7L);

        assertEquals("24", dto.getHourCycle());
        assertEquals(100, dto.getGlassIntensity());
        assertTrue(dto.getSoundEnabled());

        ArgumentCaptor<UserPreference> saved = ArgumentCaptor.forClass(UserPreference.class);
        verify(repository).save(saved.capture());
        assertEquals(7L, saved.getValue().getUserId());
    }

    @Test
    void getPreferencesReturnsStoredValues() {
        UserPreference stored = new UserPreference();
        stored.setUserId(7L);
        stored.setHourCycle("12");
        stored.setGlassIntensity(40);
        stored.setSoundEnabled(false);
        when(repository.findByUserId(7L)).thenReturn(Optional.of(stored));

        UserPreferenceDTO dto = service.getPreferences(7L);

        assertEquals("12", dto.getHourCycle());
        assertEquals(40, dto.getGlassIntensity());
        assertFalse(dto.getSoundEnabled());
        verify(repository, never()).save(any());
    }

    @Test
    void updateMergesOnlySuppliedFields() {
        UserPreference stored = new UserPreference();
        stored.setUserId(7L);
        stored.setHourCycle("24");
        stored.setGlassIntensity(80);
        stored.setSoundEnabled(true);
        when(repository.findByUserId(7L)).thenReturn(Optional.of(stored));
        when(repository.save(any(UserPreference.class))).thenAnswer(inv -> inv.getArgument(0));

        // Only the hour cycle is supplied: the other two must survive untouched.
        UserPreferenceDTO dto = service.updatePreferences(7L, new UserPreferenceDTO("12", null, null));

        assertEquals("12", dto.getHourCycle());
        assertEquals(80, dto.getGlassIntensity(), "glass intensity must not be reset");
        assertTrue(dto.getSoundEnabled(), "sound flag must not be reset");
    }

    @Test
    void updateCreatesRowWhenNoneExists() {
        when(repository.findByUserId(9L)).thenReturn(Optional.empty());
        when(repository.save(any(UserPreference.class))).thenAnswer(inv -> inv.getArgument(0));

        UserPreferenceDTO dto = service.updatePreferences(9L, new UserPreferenceDTO("12", 25, false));

        assertEquals("12", dto.getHourCycle());
        assertEquals(25, dto.getGlassIntensity());
        assertFalse(dto.getSoundEnabled());
    }

    @Test
    void updateAllowsClearingSoundFlagExplicitly() {
        UserPreference stored = new UserPreference();
        stored.setUserId(7L);
        stored.setSoundEnabled(true);
        when(repository.findByUserId(7L)).thenReturn(Optional.of(stored));
        when(repository.save(any(UserPreference.class))).thenAnswer(inv -> inv.getArgument(0));

        // false is a real value, not "absent" — it must be written through.
        UserPreferenceDTO dto = service.updatePreferences(7L, new UserPreferenceDTO(null, null, false));

        assertFalse(dto.getSoundEnabled());
    }
}
