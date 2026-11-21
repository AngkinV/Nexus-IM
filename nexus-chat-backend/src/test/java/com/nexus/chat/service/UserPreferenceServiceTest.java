package com.nexus.chat.service;

import com.nexus.chat.dto.UserPreferenceDTO;
import com.nexus.chat.model.UserPreference;
import com.nexus.chat.repository.UserPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Merge semantics and the first-row insert race, against a mocked repository and
 * transaction manager. No database is involved: the real unique constraint and
 * the column-level update are not exercised here.
 */
class UserPreferenceServiceTest {

    private final UserPreferenceRepository repository = mock(UserPreferenceRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private UserPreferenceService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        when(repository.saveAndFlush(any(UserPreference.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new UserPreferenceService(repository, transactionManager);
    }

    private UserPreference stored(String hourCycle, int glass, boolean sound) {
        UserPreference entity = new UserPreference();
        entity.setUserId(7L);
        entity.setHourCycle(hourCycle);
        entity.setGlassIntensity(glass);
        entity.setSoundEnabled(sound);
        return entity;
    }

    @Test
    void getPreferencesCreatesDefaultsOnFirstAccess() {
        when(repository.findByUserId(7L)).thenReturn(Optional.empty());

        UserPreferenceDTO dto = service.getPreferences(7L);

        assertEquals("24", dto.getHourCycle());
        assertEquals(100, dto.getGlassIntensity());
        assertTrue(dto.getSoundEnabled());
        ArgumentCaptor<UserPreference> saved = ArgumentCaptor.forClass(UserPreference.class);
        verify(repository).saveAndFlush(saved.capture());
        assertEquals(7L, saved.getValue().getUserId());
    }

    @Test
    void getPreferencesReturnsStoredValuesWithoutWriting() {
        when(repository.findByUserId(7L)).thenReturn(Optional.of(stored("12", 40, false)));

        UserPreferenceDTO dto = service.getPreferences(7L);

        assertEquals("12", dto.getHourCycle());
        assertEquals(40, dto.getGlassIntensity());
        assertFalse(dto.getSoundEnabled());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateMergesOnlySuppliedFields() {
        when(repository.findByUserId(7L)).thenReturn(Optional.of(stored("24", 80, true)));

        UserPreferenceDTO dto = service.updatePreferences(7L, new UserPreferenceDTO("12", null, null));

        assertEquals("12", dto.getHourCycle());
        assertEquals(80, dto.getGlassIntensity(), "glass intensity must not be reset");
        assertTrue(dto.getSoundEnabled(), "sound flag must not be reset");
    }

    @Test
    void updateWritesSeveralFieldsInOneRequest() {
        when(repository.findByUserId(7L)).thenReturn(Optional.of(stored("24", 100, true)));

        UserPreferenceDTO dto = service.updatePreferences(7L, new UserPreferenceDTO("12", 25, false));

        assertEquals("12", dto.getHourCycle());
        assertEquals(25, dto.getGlassIntensity());
        assertFalse(dto.getSoundEnabled());
        verify(repository, times(1)).saveAndFlush(any());
    }

    @Test
    void updateTreatsFalseAsAValueNotAsAbsent() {
        when(repository.findByUserId(7L)).thenReturn(Optional.of(stored("24", 100, true)));

        UserPreferenceDTO dto = service.updatePreferences(7L, new UserPreferenceDTO(null, null, false));

        assertFalse(dto.getSoundEnabled());
    }

    @Test
    void firstAccessRaceFallsBackToTheRowTheOtherRequestInserted() {
        // Both requests saw "no row"; ours loses the insert on the unique key.
        when(repository.findByUserId(7L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(stored("12", 60, false)));
        when(repository.saveAndFlush(any(UserPreference.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate user_id"));

        UserPreferenceDTO dto = service.getPreferences(7L);

        assertEquals("12", dto.getHourCycle(), "must return the winner's row, not fail");
        assertEquals(60, dto.getGlassIntensity());
        verify(repository, times(2)).findByUserId(7L);
        // The failed attempt's transaction is rolled back before the retry.
        verify(transactionManager, times(1)).rollback(any());
    }

    @Test
    void updateRaceOnFirstRowIsAppliedOnTopOfTheWinner() {
        UserPreference winner = stored("24", 100, true);
        when(repository.findByUserId(7L)).thenReturn(Optional.empty()).thenReturn(Optional.of(winner));
        when(repository.saveAndFlush(any(UserPreference.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate user_id"))
                .thenAnswer(inv -> inv.getArgument(0));

        UserPreferenceDTO dto = service.updatePreferences(7L, new UserPreferenceDTO(null, 30, null));

        assertEquals(30, dto.getGlassIntensity(), "our change must not be lost to the race");
        assertEquals("24", dto.getHourCycle());
    }

    @Test
    void aViolationThatIsNotARaceStillSurfaces() {
        when(repository.findByUserId(7L)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(UserPreference.class)))
                .thenThrow(new DataIntegrityViolationException("something else is wrong"));

        assertThrows(DataIntegrityViolationException.class, () -> service.getPreferences(7L));
        verify(repository, times(2)).saveAndFlush(any());
    }
}
