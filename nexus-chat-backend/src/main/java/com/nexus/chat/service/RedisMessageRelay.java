package com.nexus.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Redis Pub/Sub message relay for multi-instance WebSocket deployment.
 *
 * Design: Broadcast approach.
 * - When a WS message targets a user, the origin instance publishes to "ws:broadcast".
 * - ALL instances receive the message and check if the target user has a local session.
 * - If yes, deliver via SimpMessagingTemplate. If no, ignore.
 *
 * This avoids sticky sessions and provides simple, reliable cross-instance delivery.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisMessageRelay implements MessageListener {

    private final RedisMessageListenerContainer listenerContainer;
    private final RedisCacheService redisCacheService;
    private final SimpMessagingTemplate messagingTemplate;
    private final SimpUserRegistry userRegistry;

    private static final String BROADCAST_CHANNEL = "ws:broadcast";
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    @PostConstruct
    public void init() {
        listenerContainer.addMessageListener(this, new ChannelTopic(BROADCAST_CHANNEL));
        log.info("[Relay] Instance {} subscribed to Redis channel: {}", instanceId, BROADCAST_CHANNEL);
    }

    /**
     * Broadcast a WebSocket message to all instances via Redis Pub/Sub.
     * Called when the origin instance cannot deliver locally.
     *
     * @param targetUserId  the target user ID
     * @param destination   the STOMP destination (e.g., /topic/user.123.messages)
     * @param payload       the JSON payload string
     */
    public void broadcast(Long targetUserId, String destination, Object payload) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());

            RelayMessage relayMsg = new RelayMessage();
            relayMsg.setOriginInstance(instanceId);
            relayMsg.setTargetUserId(targetUserId);
            relayMsg.setDestination(destination);
            relayMsg.setPayload(mapper.writeValueAsString(payload));

            String json = mapper.writeValueAsString(relayMsg);
            redisCacheService.publishMessage(BROADCAST_CHANNEL, json);
        } catch (JsonProcessingException e) {
            log.error("[Relay] Failed to serialize relay message", e);
        }
    }

    /**
     * Send a message to a user, with automatic cross-instance relay.
     * If the user has a local session, deliver directly.
     * Otherwise, broadcast via Redis for other instances to pick up.
     */
    public void sendToUser(Long targetUserId, String destination, Object payload) {
        String userIdStr = String.valueOf(targetUserId);

        // Check if user has a local STOMP session
        if (userRegistry.getUser(userIdStr) != null) {
            messagingTemplate.convertAndSend(destination, payload);
        } else {
            // User not on this instance; relay via Redis
            broadcast(targetUserId, destination, payload);
        }
    }

    /**
     * Redis message listener callback.
     * Called when any instance publishes to ws:broadcast.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());

            String body = new String(message.getBody());
            RelayMessage relayMsg = mapper.readValue(body, RelayMessage.class);

            // Skip messages from this instance (already delivered locally)
            if (instanceId.equals(relayMsg.getOriginInstance())) {
                return;
            }

            // Check if the target user is connected to THIS instance
            String userIdStr = String.valueOf(relayMsg.getTargetUserId());
            if (userRegistry.getUser(userIdStr) != null) {
                // Deliver locally
                messagingTemplate.convertAndSend(relayMsg.getDestination(), relayMsg.getPayload());
                log.debug("[Relay] Delivered relayed message to user {} on instance {}",
                        relayMsg.getTargetUserId(), instanceId);
            }
        } catch (Exception e) {
            log.error("[Relay] Failed to process relayed message", e);
        }
    }

    public String getInstanceId() {
        return instanceId;
    }

    /**
     * Internal DTO for messages relayed via Redis Pub/Sub.
     */
    @Data
    public static class RelayMessage {
        private String originInstance;
        private Long targetUserId;
        private String destination;
        private String payload;
    }
}
