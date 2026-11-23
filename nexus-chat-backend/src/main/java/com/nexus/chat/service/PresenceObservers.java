package com.nexus.chat.service;

import com.nexus.chat.dto.WebSocketMessage;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.repository.ContactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import java.util.*;

/** Only reverse contacts and current co-members receive identity/presence events. */
@Service
@RequiredArgsConstructor
public class PresenceObservers {
    private final ContactRepository contacts;
    private final ChatMemberRepository members;
    private final PresenceService presence;
    private final RedisMessageRelay relay;

    public Set<Long> observers(Long userId) {
        Set<Long> result = new HashSet<>();
        contacts.findByContactUserId(userId).forEach(c -> result.add(c.getUserId()));
        members.findByUserId(userId).forEach(own ->
                members.findByChatId(own.getChatId()).forEach(m -> result.add(m.getUserId())));
        result.remove(userId);
        return result;
    }

    @EventListener
    public void changed(PresenceService.Changed event) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", event.userId());
        // Null intentionally clears hidden/unknown presence; never unbox it.
        Boolean visible = presence.getVisibleStatus(event.userId());
        payload.put("isOnline", visible);
        var lastSeen = presence.getVisibleLastSeen(event.userId());
        if (lastSeen != null) payload.put("lastSeen", lastSeen.toInstant(java.time.ZoneOffset.UTC).toString());
        WebSocketMessage message = new WebSocketMessage(WebSocketMessage.MessageType.CONTACT_STATUS_CHANGED, payload);
        for (Long observer : observers(event.userId())) {
            relay.sendToUser(observer, "/topic/user." + observer + ".contacts", message);
        }
    }
}
