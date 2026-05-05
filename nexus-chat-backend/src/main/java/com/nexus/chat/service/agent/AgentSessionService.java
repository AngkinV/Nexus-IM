package com.nexus.chat.service.agent;

import com.nexus.chat.dto.agent.AgentSessionDtos;
import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.model.agent.AgentSession;
import com.nexus.chat.repository.agent.AgentSessionRepository;
import com.nexus.chat.service.agent.memory.AgentMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the persistent metadata for AI Assistant chat sessions — the index that backs the
 * "history conversations" dropdown. Messages themselves are NOT stored here; they live in
 * Redis short-term memory ({@link AgentMemoryService}). This keeps the design tight:
 * we get the listing/rename/delete UX without a heavy message table.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSessionService {

    private static final int MAX_TITLE_LEN = 30;

    private final AgentSessionRepository repository;
    private final AgentMemoryService memoryService;

    public List<AgentSessionDtos.SessionView> listForUser(Long userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(AgentSessionService::toView)
                .toList();
    }

    @Transactional
    public AgentSessionDtos.SessionView create(Long userId, String title) {
        AgentSession entity = new AgentSession();
        entity.setUserId(userId);
        entity.setSessionId("as_" + UUID.randomUUID().toString().replace("-", ""));
        entity.setTitle(truncate(title));
        return toView(repository.save(entity));
    }

    @Transactional
    public AgentSessionDtos.SessionView rename(Long userId, String sessionId, String title) {
        AgentSession entity = mustOwn(userId, sessionId);
        entity.setTitle(truncate(title));
        return toView(repository.save(entity));
    }

    @Transactional
    public void delete(Long userId, String sessionId) {
        AgentSession entity = mustOwn(userId, sessionId);
        repository.delete(entity);
        memoryService.clearShortTerm(userId, sessionId);
    }

    /**
     * Idempotent: ensures a row exists for {@code sessionId} and bumps {@code updated_at}.
     * If the title is null/blank and {@code titleSeed} is provided (typically the first user
     * message), it is auto-set, truncated to {@link #MAX_TITLE_LEN}.
     *
     * Called from the chat path so a brand-new conversation gets a sensible title without
     * the client having to specify one upfront.
     */
    @Transactional
    public AgentSession touchAndAutoTitle(Long userId, String sessionId, String titleSeed) {
        AgentSession entity = repository.findBySessionId(sessionId)
                .orElseGet(() -> {
                    AgentSession s = new AgentSession();
                    s.setUserId(userId);
                    s.setSessionId(sessionId);
                    return s;
                });
        if (entity.getId() != null && !entity.getUserId().equals(userId)) {
            throw new AgentException(AgentErrorCode.AGENT_AUTHZ_40301, "session does not belong to this user");
        }
        entity.setUserId(userId);
        if ((entity.getTitle() == null || entity.getTitle().isBlank()) && titleSeed != null && !titleSeed.isBlank()) {
            entity.setTitle(truncate(titleSeed));
        }
        return repository.save(entity);
    }

    public List<Map<String, Object>> messagesOf(Long userId, String sessionId) {
        mustOwn(userId, sessionId);
        return memoryService.getShortTermMessages(userId, sessionId).stream()
                .map(m -> Map.<String, Object>of(
                        "role", m.getOrDefault("role", "unknown"),
                        "content", m.getOrDefault("content", "")
                ))
                .toList();
    }

    private AgentSession mustOwn(Long userId, String sessionId) {
        AgentSession entity = repository.findBySessionId(sessionId)
                .orElseThrow(() -> new AgentException(AgentErrorCode.AGENT_SESSION_40401, "session not found"));
        if (!entity.getUserId().equals(userId)) {
            throw new AgentException(AgentErrorCode.AGENT_AUTHZ_40301, "session does not belong to this user");
        }
        return entity;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        String trimmed = s.trim().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) return null;
        return trimmed.length() <= MAX_TITLE_LEN ? trimmed : trimmed.substring(0, MAX_TITLE_LEN);
    }

    private static AgentSessionDtos.SessionView toView(AgentSession m) {
        AgentSessionDtos.SessionView v = new AgentSessionDtos.SessionView();
        v.setSessionId(m.getSessionId());
        v.setTitle(m.getTitle());
        v.setCreatedAt(m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
        v.setUpdatedAt(m.getUpdatedAt() == null ? null : m.getUpdatedAt().toString());
        return v;
    }
}
