package com.nexus.chat.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CompanionConversationGuard {
    private record Key(Long userId, Long roleId) {}
    private final Set<Key> busy = ConcurrentHashMap.newKeySet();

    // Keep the guard through model calls without holding a database transaction open.
    public Lease acquire(Long userId, Long roleId) {
        Key key = new Key(userId, roleId);
        if (!busy.add(key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "conversation is busy");
        }
        return () -> busy.remove(key);
    }

    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
