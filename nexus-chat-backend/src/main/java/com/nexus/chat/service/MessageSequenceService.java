package com.nexus.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Atomic message sequence number generator backed by Redis INCR.
 * Each chat has its own monotonically increasing sequence counter.
 * Guarantees ordering within a chat across all instances.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageSequenceService {

    private final RedisCacheService redisCacheService;

    /**
     * Generate the next sequence number for a chat.
     * Uses Redis INCR for atomic increment (safe for concurrent access).
     */
    public long nextSequenceNumber(Long chatId) {
        return redisCacheService.getNextSequenceNumber(chatId);
    }
}
