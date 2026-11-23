package com.nexus.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.dto.GroupDTO;
import com.nexus.chat.dto.GroupMemberDTO;
import com.nexus.chat.dto.WebSocketMessage;
import com.nexus.chat.model.ChatMember;
import com.nexus.chat.model.User;
import com.nexus.chat.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PresenceSnapshotAndObserverTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void lastSeenWireFormatPreservesRedisInstantRegardlessOfTimestampSetting(boolean timestamps) throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        // true is also the default used by the relay's local mapper.
        assertTrue(mapper.isEnabled(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        mapper.configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, timestamps);
        var strings = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> values =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(strings.opsForValue()).thenReturn(values);
        long millis = 1763812345123L; // Nonzero milliseconds must survive the wire.
        when(values.get("presence:last-seen:7")).thenReturn(Long.toString(millis));
        RedisCacheService cache = new RedisCacheService(null, strings);
        var privacy = mock(UserPrivacySettingsRepository.class);
        when(privacy.findByUserId(7L)).thenReturn(Optional.empty());
        PresenceService presence = new PresenceService(cache, privacy,
                mock(org.springframework.context.ApplicationEventPublisher.class));
        var utc = presence.getVisibleLastSeen(7L);
        assertNotNull(utc);

        var user = new com.nexus.chat.dto.UserDTO(); user.setLastSeen(utc);
        var contact = new com.nexus.chat.dto.ContactDTO(); contact.setLastSeen(utc);
        var member = new GroupMemberDTO(); member.setLastSeen(utc);
        var profile = new com.nexus.chat.dto.UserProfileDTO(); profile.setLastSeen(utc);
        for (Object dto : List.of(user, contact, member, profile)) {
            var json = mapper.readTree(mapper.writeValueAsString(dto));
            assertUtcMillis(json.get("lastSeen"), millis);
        }

        ContactRepository contacts = mock(ContactRepository.class);
        ChatMemberRepository members = mock(ChatMemberRepository.class);
        RedisMessageRelay relay = mock(RedisMessageRelay.class);
        ChatMember own = new ChatMember(); own.setChatId(12L); own.setUserId(7L);
        ChatMember peer = new ChatMember(); peer.setChatId(12L); peer.setUserId(8L);
        when(contacts.findByContactUserId(7L)).thenReturn(List.of());
        when(members.findByUserId(7L)).thenReturn(List.of(own));
        when(members.findByChatId(12L)).thenReturn(List.of(own, peer));
        PresenceObservers observers = new PresenceObservers(contacts, members, presence, relay);
        observers.changed(new PresenceService.Changed(7L, false));
        ArgumentCaptor<WebSocketMessage> message = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(relay).sendToUser(eq(8L), eq("/topic/user.8.contacts"), message.capture());
        assertUtcMillis(mapper.readTree(mapper.writeValueAsString(message.getValue()))
                .get("payload").get("lastSeen"), millis);

        // Both a missing Redis timestamp and privacy suppression must remain absent/null.
        var hidden = new com.nexus.chat.model.UserPrivacySettings(); hidden.setShowLastSeen(false);
        for (boolean hide : new boolean[] {false, true}) {
            when(values.get("presence:last-seen:7")).thenReturn(hide ? Long.toString(millis) : null);
            when(privacy.findByUserId(7L)).thenReturn(hide ? Optional.of(hidden) : Optional.empty());
            var absent = presence.getVisibleLastSeen(7L);
            assertNull(absent);
            user.setLastSeen(absent); contact.setLastSeen(absent);
            member.setLastSeen(absent); profile.setLastSeen(absent);
            for (Object dto : List.of(user, contact, member, profile)) {
                var json = mapper.readTree(mapper.writeValueAsString(dto));
                assertTrue(json.get("lastSeen").isNull());
            }
            clearInvocations(relay);
            observers.changed(new PresenceService.Changed(7L, false));
            verify(relay).sendToUser(eq(8L), eq("/topic/user.8.contacts"), message.capture());
            assertFalse(mapper.readTree(mapper.writeValueAsString(message.getValue()))
                    .get("payload").has("lastSeen"));
        }
    }

    private static void assertUtcMillis(com.fasterxml.jackson.databind.JsonNode value, long millis) {
        assertTrue(value.isTextual(), "lastSeen must never be a timestamp array");
        assertTrue(value.asText().endsWith("Z"), "lastSeen must explicitly identify UTC");
        assertEquals(millis, java.time.Instant.parse(value.asText()).toEpochMilli());
    }

    @Test
    void httpLoginFlagDoesNotDetermineUserSnapshot() {
        UserRepository users = mock(UserRepository.class);
        PresenceService presence = mock(PresenceService.class);
        // Construct the service without a Spring context; only this read path's
        // dependencies are required, all other repository operations remain unused.
        UserService service = (UserService) org.springframework.beans.BeanUtils.instantiateClass(
                UserService.class.getDeclaredConstructors()[0],
                constructorArguments(UserService.class, users, presence));
        User user = new User();
        user.setId(7L);
        user.setIsOnline(true); // Exactly the historical HTTP-login DB flag.
        user.setAvatarUrl("/uploads/avatars/real.png");
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(presence.getVisibleStatus(7L)).thenReturn(false);
        var dto = service.getUserById(7L);
        assertEquals(Boolean.FALSE, dto.getIsOnline());
        assertEquals(user.getAvatarUrl(), dto.getAvatarUrl());
        assertNull(dto.getLastSeen());
    }

    private Object[] constructorArguments(Class<?> type, UserRepository users, PresenceService presence) {
        return Arrays.stream(type.getDeclaredConstructors()[0].getParameterTypes())
                .map(parameter -> parameter == UserRepository.class ? users :
                        parameter == PresenceService.class ? presence : mock(parameter)).toArray();
    }

    @Test
    void nonFriendCoMemberReceivesPrivateStatusAndHiddenStatusIsNull() {
        ContactRepository contacts = mock(ContactRepository.class);
        ChatMemberRepository members = mock(ChatMemberRepository.class);
        PresenceService presence = mock(PresenceService.class);
        RedisMessageRelay relay = mock(RedisMessageRelay.class);
        PresenceObservers observers = new PresenceObservers(contacts, members, presence, relay);
        ChatMember own = new ChatMember(); own.setChatId(12L); own.setUserId(7L);
        ChatMember peer = new ChatMember(); peer.setChatId(12L); peer.setUserId(8L);
        when(contacts.findByContactUserId(7L)).thenReturn(List.of());
        when(members.findByUserId(7L)).thenReturn(List.of(own));
        when(members.findByChatId(12L)).thenReturn(List.of(own, peer));
        when(presence.getVisibleStatus(7L)).thenReturn(null);
        observers.changed(new PresenceService.Changed(7L, true));
        ArgumentCaptor<WebSocketMessage> message = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(relay).sendToUser(eq(8L), eq("/topic/user.8.contacts"), message.capture());
        var json = new ObjectMapper().valueToTree(message.getValue());
        assertEquals("CONTACT_STATUS_CHANGED", json.get("type").asText());
        assertEquals(7L, json.get("payload").get("userId").asLong());
        assertTrue(json.get("payload").get("isOnline").isNull());
        verifyNoMoreInteractions(relay);
    }

    @Test
    void groupMembersHaveBooleanPresenceAndGroupHasNoPresence() {
        GroupMemberDTO member = new GroupMemberDTO();
        member.setId(8L); member.setAvatarUrl("/uploads/avatars/8.png"); member.setIsOnline(true);
        GroupDTO group = new GroupDTO(); group.setId(12L); group.setAvatar("/uploads/groups/12.png");
        group.setMembers(List.of(member));
        var json = new ObjectMapper().valueToTree(group);
        assertFalse(json.has("isOnline"));
        assertEquals("/uploads/groups/12.png", json.get("avatar").asText());
        assertTrue(json.get("members").get(0).get("isOnline").isBoolean());
        assertEquals(8L, json.get("members").get(0).get("id").asLong());
        assertEquals("/uploads/avatars/8.png", json.get("members").get(0).get("avatarUrl").asText());
    }
}
