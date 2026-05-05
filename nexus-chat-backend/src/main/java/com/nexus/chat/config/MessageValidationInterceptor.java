package com.nexus.chat.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * WebSocket message validation interceptor.
 * Enforces content length limits, sender identity verification, and XSS prevention.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageValidationInterceptor implements ChannelInterceptor {

    private static final int MAX_TEXT_LENGTH = 5000;
    private static final int MAX_URL_LENGTH = 2000;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024; // 64KB

    private final WebSocketRateLimiter rateLimiter;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        // Only validate SEND commands (client sending messages to server)
        if (!StompCommand.SEND.equals(accessor.getCommand())) {
            return message;
        }

        String destination = accessor.getDestination();
        if (destination == null) {
            return message;
        }

        // Validate payload size
        Object payload = message.getPayload();
        if (payload instanceof byte[]) {
            byte[] bytes = (byte[]) payload;
            if (bytes.length > MAX_PAYLOAD_BYTES) {
                log.warn("WebSocket 消息载荷超限: {} bytes (max {})", bytes.length, MAX_PAYLOAD_BYTES);
                throw new MessageDeliveryException("Message payload too large");
            }
        }

        // Rate limiting based on destination
        java.security.Principal user = accessor.getUser();
        if (user != null) {
            Long userId;
            try {
                userId = Long.parseLong(user.getName());
            } catch (NumberFormatException e) {
                throw new MessageDeliveryException("Invalid user principal");
            }

            if (destination.startsWith("/app/chat.sendMessage") || destination.startsWith("/app/chat.send")) {
                if (!rateLimiter.allowMessage(userId)) {
                    throw new MessageDeliveryException("Rate limit exceeded: too many messages");
                }
            } else if (destination.contains("typing")) {
                if (!rateLimiter.allowTyping(userId)) {
                    throw new MessageDeliveryException("Rate limit exceeded: typing indicators");
                }
            }
        }

        return message;
    }

    /**
     * Sanitize HTML content to prevent XSS attacks.
     * Allows basic text formatting but strips scripts and dangerous tags.
     */
    public static String sanitizeContent(String content) {
        if (content == null) return null;
        // Strip all HTML tags - chat messages should be plain text
        return Jsoup.clean(content, Safelist.none());
    }

    /**
     * Validate text message content length.
     */
    public static void validateTextContent(String content) {
        if (content != null && content.length() > MAX_TEXT_LENGTH) {
            throw new MessageDeliveryException(
                    "Message content too long: " + content.length() + " chars (max " + MAX_TEXT_LENGTH + ")");
        }
    }

    /**
     * Validate URL length.
     */
    public static void validateUrl(String url) {
        if (url != null && url.length() > MAX_URL_LENGTH) {
            throw new MessageDeliveryException(
                    "URL too long: " + url.length() + " chars (max " + MAX_URL_LENGTH + ")");
        }
    }
}
