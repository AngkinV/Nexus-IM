package com.nexus.chat.service;

import com.nexus.chat.dto.WebSocketMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RedisCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    // Key prefixes
    private static final String PRESENCE_PREFIX = "presence:";
    private static final String PRESENCE_SESSIONS_PREFIX = "presence:sessions:";
    private static final String PRESENCE_ONLINE_SET = "presence:online";
    private static final String USER_PROFILE_PREFIX = "user:profile:";
    private static final String USER_CONTACTS_PREFIX = "user:contacts:";
    private static final String USER_CHATLIST_PREFIX = "user:chatlist:";
    private static final String CHAT_MEMBERS_PREFIX = "chat:members:";
    private static final String CHAT_LAST_MSG_PREFIX = "chat:lastmsg:";
    private static final String UNREAD_PREFIX = "user:unread:";
    private static final String TYPING_PREFIX = "chat:typing:";
    private static final String OFFLINE_QUEUE_PREFIX = "offline:";
    private static final String CHAT_SEQ_PREFIX = "chat:seq:";
    private static final String WS_SESSIONS_PREFIX = "ws:sessions:";
    private static final String PENDING_REQS_PREFIX = "user:pendingreqs:";

    public RedisCacheService(RedisTemplate<String, Object> redisTemplate,
                             StringRedisTemplate stringRedisTemplate) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    // ==================== Presence Management ====================

    public void setUserOnline(Long userId, String sessionId, String instanceId) {
        String presenceKey = PRESENCE_PREFIX + userId;
        String sessionsKey = PRESENCE_SESSIONS_PREFIX + userId;

        stringRedisTemplate.opsForSet().add(sessionsKey, sessionId);
        stringRedisTemplate.opsForValue().set(presenceKey, instanceId, 90, TimeUnit.SECONDS);
        stringRedisTemplate.opsForSet().add(PRESENCE_ONLINE_SET, String.valueOf(userId));

        log.debug("User {} online, session: {}, instance: {}", userId, sessionId, instanceId);
    }

    public void refreshPresence(Long userId) {
        String presenceKey = PRESENCE_PREFIX + userId;
        stringRedisTemplate.expire(presenceKey, 90, TimeUnit.SECONDS);
    }

    public void setUserOffline(Long userId, String sessionId) {
        String sessionsKey = PRESENCE_SESSIONS_PREFIX + userId;

        if (sessionId != null) {
            stringRedisTemplate.opsForSet().remove(sessionsKey, sessionId);
        }

        Long remainingSessions = stringRedisTemplate.opsForSet().size(sessionsKey);
        if (remainingSessions == null || remainingSessions == 0) {
            stringRedisTemplate.delete(PRESENCE_PREFIX + userId);
            stringRedisTemplate.opsForSet().remove(PRESENCE_ONLINE_SET, String.valueOf(userId));
            log.debug("User {} fully offline", userId);
            return;
        }
        log.debug("User {} session removed, {} sessions remaining", userId, remainingSessions);
    }

    public boolean isUserOnline(Long userId) {
        return Boolean.TRUE.equals(
                stringRedisTemplate.hasKey(PRESENCE_PREFIX + userId));
    }

    public Map<Long, Boolean> getOnlineStatuses(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Boolean> result = new HashMap<>();
        for (Long userId : userIds) {
            result.put(userId, isUserOnline(userId));
        }
        return result;
    }

    public Set<Long> getOnlineUserIds() {
        Set<String> members = stringRedisTemplate.opsForSet().members(PRESENCE_ONLINE_SET);
        if (members == null) return Collections.emptySet();
        return members.stream()
                .map(Long::parseLong)
                .collect(Collectors.toSet());
    }

    // ==================== User Profile Cache ====================

    public void cacheUserProfile(Long userId, Object profileDto) {
        String key = USER_PROFILE_PREFIX + userId;
        redisTemplate.opsForValue().set(key, profileDto, 10, TimeUnit.MINUTES);
    }

    @SuppressWarnings("unchecked")
    public <T> T getCachedUserProfile(Long userId) {
        String key = USER_PROFILE_PREFIX + userId;
        return (T) redisTemplate.opsForValue().get(key);
    }

    public void invalidateUserProfile(Long userId) {
        redisTemplate.delete(USER_PROFILE_PREFIX + userId);
    }

    // ==================== User Contacts Cache ====================

    public void cacheUserContacts(Long userId, Object contacts) {
        String key = USER_CONTACTS_PREFIX + userId;
        redisTemplate.opsForValue().set(key, contacts, 5, TimeUnit.MINUTES);
    }

    @SuppressWarnings("unchecked")
    public <T> T getCachedUserContacts(Long userId) {
        String key = USER_CONTACTS_PREFIX + userId;
        return (T) redisTemplate.opsForValue().get(key);
    }

    public void invalidateUserContacts(Long userId) {
        redisTemplate.delete(USER_CONTACTS_PREFIX + userId);
    }

    // ==================== Chat List Cache ====================

    public void cacheUserChatList(Long userId, Object chatList) {
        String key = USER_CHATLIST_PREFIX + userId;
        redisTemplate.opsForValue().set(key, chatList, 3, TimeUnit.MINUTES);
    }

    @SuppressWarnings("unchecked")
    public <T> T getCachedUserChatList(Long userId) {
        String key = USER_CHATLIST_PREFIX + userId;
        return (T) redisTemplate.opsForValue().get(key);
    }

    public void invalidateUserChatList(Long userId) {
        redisTemplate.delete(USER_CHATLIST_PREFIX + userId);
    }

    // ==================== Chat Members Cache ====================

    public void cacheChatMembers(Long chatId, List<Long> memberIds) {
        String key = CHAT_MEMBERS_PREFIX + chatId;
        redisTemplate.opsForValue().set(key, memberIds, 5, TimeUnit.MINUTES);
    }

    @SuppressWarnings("unchecked")
    public List<Long> getCachedChatMembers(Long chatId) {
        String key = CHAT_MEMBERS_PREFIX + chatId;
        return (List<Long>) redisTemplate.opsForValue().get(key);
    }

    public void invalidateChatMembers(Long chatId) {
        redisTemplate.delete(CHAT_MEMBERS_PREFIX + chatId);
    }

    // ==================== Unread Counts ====================

    public void incrementUnread(Long userId, Long chatId) {
        String key = UNREAD_PREFIX + userId + ":" + chatId;
        stringRedisTemplate.opsForValue().increment(key);
    }

    public void resetUnread(Long userId, Long chatId) {
        String key = UNREAD_PREFIX + userId + ":" + chatId;
        stringRedisTemplate.delete(key);
    }

    public int getUnread(Long userId, Long chatId) {
        String key = UNREAD_PREFIX + userId + ":" + chatId;
        String value = stringRedisTemplate.opsForValue().get(key);
        return value != null ? Integer.parseInt(value) : 0;
    }

    // ==================== Typing Indicators ====================

    public void setTyping(Long chatId, Long userId) {
        String key = TYPING_PREFIX + chatId + ":" + userId;
        stringRedisTemplate.opsForValue().set(key, "1", 5, TimeUnit.SECONDS);
    }

    public boolean isTyping(Long chatId, Long userId) {
        String key = TYPING_PREFIX + chatId + ":" + userId;
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }

    // ==================== Offline Message Queue ====================

    public void queueOfflineMessage(Long userId, WebSocketMessage message) {
        try {
            String key = OFFLINE_QUEUE_PREFIX + userId;
            String json = objectMapper.writeValueAsString(message);
            stringRedisTemplate.opsForList().rightPush(key, json);
            // Set TTL of 7 days for offline queue
            stringRedisTemplate.expire(key, 7, TimeUnit.DAYS);
            log.debug("Queued offline message for user {}", userId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize offline message for user {}", userId, e);
        }
    }

    public List<String> drainOfflineQueue(Long userId) {
        String key = OFFLINE_QUEUE_PREFIX + userId;
        List<String> messages = new ArrayList<>();
        String msg;
        while ((msg = stringRedisTemplate.opsForList().leftPop(key)) != null) {
            messages.add(msg);
        }
        if (!messages.isEmpty()) {
            log.info("Drained {} offline messages for user {}", messages.size(), userId);
        }
        return messages;
    }

    // ==================== Message Sequence ====================

    public Long getNextSequenceNumber(Long chatId) {
        String key = CHAT_SEQ_PREFIX + chatId;
        return stringRedisTemplate.opsForValue().increment(key);
    }

    // ==================== WebSocket Session Registry ====================

    public void registerWsSession(Long userId, String sessionInfo) {
        String key = WS_SESSIONS_PREFIX + userId;
        stringRedisTemplate.opsForSet().add(key, sessionInfo);
    }

    public void unregisterWsSession(Long userId, String sessionInfo) {
        String key = WS_SESSIONS_PREFIX + userId;
        stringRedisTemplate.opsForSet().remove(key, sessionInfo);
    }

    public Set<String> getWsSessions(Long userId) {
        String key = WS_SESSIONS_PREFIX + userId;
        return stringRedisTemplate.opsForSet().members(key);
    }

    // ==================== Pending Requests Cache ====================

    public void cachePendingRequests(Long userId, Object requests) {
        String key = PENDING_REQS_PREFIX + userId;
        redisTemplate.opsForValue().set(key, requests, 2, TimeUnit.MINUTES);
    }

    public void invalidatePendingRequests(Long userId) {
        redisTemplate.delete(PENDING_REQS_PREFIX + userId);
    }

    // ==================== Bulk Cache Invalidation ====================

    public void invalidateAllUserCaches(Long userId) {
        redisTemplate.delete(USER_PROFILE_PREFIX + userId);
        redisTemplate.delete(USER_CONTACTS_PREFIX + userId);
        redisTemplate.delete(USER_CHATLIST_PREFIX + userId);
        redisTemplate.delete(PENDING_REQS_PREFIX + userId);
    }

    public void invalidateChatCaches(Long chatId, List<Long> memberIds) {
        redisTemplate.delete(CHAT_MEMBERS_PREFIX + chatId);
        if (memberIds != null) {
            for (Long memberId : memberIds) {
                redisTemplate.delete(USER_CHATLIST_PREFIX + memberId);
            }
        }
    }

    // ==================== Redis Pub/Sub for Multi-Instance ====================

    public void publishMessage(String channel, String message) {
        stringRedisTemplate.convertAndSend(channel, message);
    }
}
