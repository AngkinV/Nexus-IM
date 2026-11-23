package com.nexus.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.dto.WebSocketMessage;
import com.nexus.chat.repository.ContactRepository;
import com.nexus.chat.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserProfilePresencePayloadTest {
    @Test
    void avatarOnlyAndNicknameOnlyDoNotClearOtherField() {
        PresenceObservers observers = mock(PresenceObservers.class);
        PresenceService presence = mock(PresenceService.class);
        RedisMessageRelay relay = mock(RedisMessageRelay.class);
        UserController controller = new UserController(mock(UserService.class), observers,
                mock(UserPreferenceService.class), mock(ContactRepository.class), presence,
                mock(RedisCacheService.class), relay);
        when(observers.observers(7L)).thenReturn(Set.of(8L));
        when(presence.isUserOnline(8L)).thenReturn(true);
        ReflectionTestUtils.invokeMethod(controller, "notifyProfileUpdate", 7L, "/uploads/avatars/7.png", null);
        ReflectionTestUtils.invokeMethod(controller, "notifyProfileUpdate", 7L, null, "new name");
        ArgumentCaptor<WebSocketMessage> messages = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(relay, times(2)).sendToUser(eq(8L), eq("/topic/user.8.messages"), messages.capture());
        ObjectMapper mapper = new ObjectMapper();
        var avatar = mapper.valueToTree(messages.getAllValues().get(0));
        assertEquals("USER_PROFILE_UPDATED", avatar.get("type").asText());
        assertEquals("/uploads/avatars/7.png", avatar.get("payload").get("avatarUrl").asText());
        assertFalse(avatar.get("payload").has("nickname"));
        var nickname = mapper.valueToTree(messages.getAllValues().get(1));
        assertEquals("new name", nickname.get("payload").get("nickname").asText());
        assertFalse(nickname.get("payload").has("avatarUrl"));
    }
}
