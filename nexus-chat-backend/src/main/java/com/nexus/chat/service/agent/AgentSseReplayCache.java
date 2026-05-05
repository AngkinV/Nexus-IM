package com.nexus.chat.service.agent;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentSseReplayCache {

    private static final long WINDOW_MS = 60_000L;

    private final Map<String, List<CachedEvent>> cache = new ConcurrentHashMap<>();

    public void append(String streamKey, long id, String event, String data) {
        cleanup(streamKey);
        cache.computeIfAbsent(streamKey, k -> new ArrayList<>())
                .add(new CachedEvent(id, event, data, System.currentTimeMillis()));
    }

    public List<CachedEvent> replayAfter(String streamKey, long lastEventId) {
        cleanup(streamKey);
        List<CachedEvent> events = cache.get(streamKey);
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream()
                .filter(e -> e.id() > lastEventId)
                .sorted(Comparator.comparingLong(CachedEvent::id))
                .toList();
    }

    public boolean hasStream(String streamKey) {
        cleanup(streamKey);
        List<CachedEvent> events = cache.get(streamKey);
        return events != null && !events.isEmpty();
    }

    private void cleanup(String streamKey) {
        List<CachedEvent> events = cache.get(streamKey);
        if (events == null) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        events.removeIf(e -> now - e.timestampMs() > WINDOW_MS);
        if (events.isEmpty()) {
            cache.remove(streamKey);
        }
    }

    public record CachedEvent(long id, String event, String data, long timestampMs) {}
}
