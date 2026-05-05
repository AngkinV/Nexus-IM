package com.nexus.chat.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Token-bucket rate limiter backed by Redis.
 * Limits WebSocket operations per user to prevent abuse.
 */
@Slf4j
@Component
public class WebSocketRateLimiter {

    private final StringRedisTemplate redisTemplate;

    // Rate limit configurations
    private static final int MESSAGE_LIMIT = 30;          // max messages
    private static final int MESSAGE_WINDOW_SECONDS = 10;  // per 10 seconds
    private static final int TYPING_LIMIT = 5;
    private static final int TYPING_WINDOW_SECONDS = 10;
    private static final int STATUS_LIMIT = 2;
    private static final int STATUS_WINDOW_SECONDS = 10;

    public WebSocketRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Check if a message send is allowed for the user.
     * @return true if allowed, false if rate limited
     */
    public boolean allowMessage(Long userId) {
        return checkRate("ratelimit:msg:" + userId, MESSAGE_LIMIT, MESSAGE_WINDOW_SECONDS);
    }

    /**
     * Check if a typing indicator is allowed for the user.
     */
    public boolean allowTyping(Long userId) {
        return checkRate("ratelimit:typing:" + userId, TYPING_LIMIT, TYPING_WINDOW_SECONDS);
    }

    /**
     * Check if a status update is allowed for the user.
     */
    public boolean allowStatusUpdate(Long userId) {
        return checkRate("ratelimit:status:" + userId, STATUS_LIMIT, STATUS_WINDOW_SECONDS);
    }

    /**
     * Simple sliding window counter using Redis INCR + TTL.
     */
    private boolean checkRate(String key, int limit, int windowSeconds) {
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                // First request in window - set expiry
                redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
            }
            if (count != null && count > limit) {
                log.warn("速率限制触发: key={}, count={}, limit={}", key, count, limit);
                return false;
            }
            return true;
        } catch (Exception e) {
            // If Redis is down, allow the request (fail-open)
            log.error("速率限制检查失败，默认放行: {}", e.getMessage());
            return true;
        }
    }
}
