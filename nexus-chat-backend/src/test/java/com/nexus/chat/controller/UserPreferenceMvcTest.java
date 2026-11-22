package com.nexus.chat.controller;

import com.nexus.chat.dto.UserPreferenceDTO;
import com.nexus.chat.exception.GlobalExceptionHandler;
import com.nexus.chat.repository.ContactRepository;
import com.nexus.chat.service.PresenceService;
import com.nexus.chat.service.RedisCacheService;
import com.nexus.chat.service.RedisMessageRelay;
import com.nexus.chat.service.UserPreferenceService;
import com.nexus.chat.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of /api/users/{id}/preferences: ownership, validation and status
 * codes. The service is mocked; persistence is covered by its own test.
 */
class UserPreferenceMvcTest {
    private static final long ME = 7, SOMEONE_ELSE = 8;

    private final UserPreferenceService preferences = mock(UserPreferenceService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(ME, null, List.of()));
        UserController controller = new UserController(
                mock(UserService.class), preferences, mock(ContactRepository.class),
                mock(PresenceService.class), mock(RedisCacheService.class), mock(RedisMessageRelay.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownerReadsOwnPreferences() throws Exception {
        when(preferences.getPreferences(ME)).thenReturn(new UserPreferenceDTO("12", 40, false));

        mvc.perform(get("/api/users/{id}/preferences", ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hourCycle").value("12"))
                .andExpect(jsonPath("$.glassIntensity").value(40))
                .andExpect(jsonPath("$.soundEnabled").value(false));
    }

    @Test
    void readingAnotherUsersPreferencesIsForbidden() throws Exception {
        mvc.perform(get("/api/users/{id}/preferences", SOMEONE_ELSE)).andExpect(status().isForbidden());
        verifyNoInteractions(preferences);
    }

    @Test
    void writingAnotherUsersPreferencesIsForbidden() throws Exception {
        mvc.perform(put("/api/users/{id}/preferences", SOMEONE_ELSE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hourCycle\":\"12\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(preferences);
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/users/{id}/preferences", ME)).andExpect(status().isUnauthorized());
        verifyNoInteractions(preferences);
    }

    @Test
    void partialUpdatePassesOnlySuppliedFields() throws Exception {
        when(preferences.updatePreferences(eq(ME), any())).thenReturn(new UserPreferenceDTO("24", 35, true));

        mvc.perform(put("/api/users/{id}/preferences", ME)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"glassIntensity\":35}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.glassIntensity").value(35));

        verify(preferences).updatePreferences(eq(ME), argThat(dto ->
                dto.getGlassIntensity() == 35 && dto.getHourCycle() == null && dto.getSoundEnabled() == null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"hourCycle\":\"25\"}",
            "{\"hourCycle\":\"\"}",
            "{\"glassIntensity\":-1}",
            "{\"glassIntensity\":101}"
    })
    void invalidValuesAreRejectedWith400AndNeverReachTheService(String body) throws Exception {
        mvc.perform(put("/api/users/{id}/preferences", ME).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(preferences);
    }

    @Test
    void unexpectedServerFailureIs500NotAClientError() throws Exception {
        when(preferences.updatePreferences(eq(ME), any())).thenThrow(new IllegalStateException("store unavailable"));
        mvc.perform(put("/api/users/{id}/preferences", ME)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hourCycle\":\"12\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Could not save preferences"));
    }
    @Test
    void unavailableStorageIs503AndCanBeRetried() throws Exception {
        when(preferences.getPreferences(ME)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("connection down"));
        mvc.perform(get("/api/users/{id}/preferences", ME))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Preference storage unavailable"));
    }
}
