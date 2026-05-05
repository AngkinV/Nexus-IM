package com.nexus.chat.service.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.model.agent.AgentLongMemory;
import com.nexus.chat.model.agent.AgentMemoryAudit;
import com.nexus.chat.repository.agent.AgentLongMemoryRepository;
import com.nexus.chat.repository.agent.AgentMemoryAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemoryService {

    private static final Duration SHORT_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentLongMemoryRepository longMemoryRepository;
    private final AgentMemoryAuditRepository memoryAuditRepository;

    public void appendShortTermMessage(Long userId, String sessionId, String role, String content) {
        String key = "agent:ctx:" + userId + ":" + sessionId + ":messages";
        try {
            String item = objectMapper.writeValueAsString(Map.of("role", role, "content", content));
            redisTemplate.opsForList().rightPush(key, item);
            Long size = redisTemplate.opsForList().size(key);
            if (size != null && size > 50) {
                redisTemplate.opsForList().trim(key, size - 50, size - 1);
            }
            redisTemplate.expire(key, SHORT_TTL);
        } catch (Exception e) {
            log.warn("append short-term memory failed: userId={}, sessionId={}", userId, sessionId, e);
        }
    }

    public List<Map<String, String>> getShortTermMessages(Long userId, String sessionId) {
        String key = "agent:ctx:" + userId + ":" + sessionId + ":messages";
        List<String> raw = redisTemplate.opsForList().range(key, 0, -1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream().map(s -> {
            try {
                return objectMapper.readValue(s, new TypeReference<Map<String, String>>() {});
            } catch (Exception e) {
                return Map.<String, String>of("role", "unknown", "content", "");
            }
        }).toList();
    }

    public void writeLongTermMemoryIfNeeded(Long userId,
                                            String memoryType,
                                            String content,
                                            double confidence,
                                            String sourceSessionId,
                                            String sourceTraceId) {
        if (confidence < 0.75d || content == null || content.isBlank()) {
            return;
        }
        AgentLongMemory memory = new AgentLongMemory();
        memory.setUserId(userId);
        memory.setMemoryType(memoryType);
        memory.setContent(maskSensitive(content));
        memory.setConfidence(BigDecimal.valueOf(confidence));
        memory.setSourceSessionId(sourceSessionId);
        memory.setSourceTraceId(sourceTraceId);
        memory.setIsActive(true);
        AgentLongMemory saved = longMemoryRepository.save(memory);

        AgentMemoryAudit audit = new AgentMemoryAudit();
        audit.setMemoryId(saved.getId());
        audit.setAction("CREATE");
        audit.setOperatorType("SYSTEM");
        audit.setOperatorId(userId);
        audit.setReason("auto_extract");
        memoryAuditRepository.save(audit);
    }

    public List<AgentLongMemory> getLongTermTopK(Long userId) {
        return longMemoryRepository.findTop8ByUserIdAndIsActiveTrueOrderByUpdatedAtDesc(userId);
    }

    public void clearShortTerm(Long userId, String sessionId) {
        redisTemplate.delete("agent:ctx:" + userId + ":" + sessionId + ":messages");
        redisTemplate.delete("agent:ctx:" + userId + ":" + sessionId);
        redisTemplate.delete("agent:tool:" + userId + ":" + sessionId);
    }

    public void disableLongTerm(Long memoryId, Long operatorUserId, String reason) {
        AgentLongMemory memory = longMemoryRepository.findById(memoryId).orElseThrow();
        memory.setIsActive(false);
        longMemoryRepository.save(memory);

        AgentMemoryAudit audit = new AgentMemoryAudit();
        audit.setMemoryId(memoryId);
        audit.setAction("DISABLE");
        audit.setOperatorType("USER");
        audit.setOperatorId(operatorUserId);
        audit.setReason(reason);
        memoryAuditRepository.save(audit);
    }

    private String maskSensitive(String text) {
        return text
                .replaceAll("(1\\d{2})\\d{4}(\\d{4})", "$1****$2")
                .replaceAll("([a-zA-Z0-9._%+-])[a-zA-Z0-9._%+-]*@([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})", "$1***@$2");
    }
}
