package com.nexus.chat.service.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.model.agent.AgentLongMemory;
import com.nexus.chat.model.agent.AgentMemoryAudit;
import com.nexus.chat.repository.agent.AgentLongMemoryRepository;
import com.nexus.chat.repository.agent.AgentMemoryAuditRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AgentMemoryService {

    private static final Duration SHORT_TTL = Duration.ofDays(7);

    // This template is pinned to the Redis DB the Python agent writes to, not the
    // app's default one. See AgentMemoryRedisConfig.
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentLongMemoryRepository longMemoryRepository;
    private final AgentMemoryAuditRepository memoryAuditRepository;

    public AgentMemoryService(
            @Qualifier("agentMemoryRedisTemplate") StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AgentLongMemoryRepository longMemoryRepository,
            AgentMemoryAuditRepository memoryAuditRepository) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.longMemoryRepository = longMemoryRepository;
        this.memoryAuditRepository = memoryAuditRepository;
    }

    public void appendShortTermMessage(Long userId, String sessionId, String role, String content) {
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"Scoped grant required");
    }

    public static String sessionPrefix(Long actor,String session) {
        return "agent:ctx:"+actor+":"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(session.getBytes(java.nio.charset.StandardCharsets.UTF_8))+":scope:";
    }
    public static String messagesKey(com.nexus.chat.service.agent.scope.ScopeDtos.AuthorizedScope s) {
        return sessionPrefix(s.actorUserId(),s.sessionId())+s.scopeKey()+":messages";
    }
    @org.springframework.transaction.annotation.Transactional
    public void appendShortTermMessage(com.nexus.chat.service.agent.scope.ScopeDtos.AuthorizedScope scope,String role,String content) {
        validate(scope);
        if(!java.util.Set.of("user","assistant").contains(role)) throw new IllegalArgumentException("Invalid role");
        String key=messagesKey(scope);
        try {
            redisTemplate.opsForList().rightPush(key,objectMapper.writeValueAsString(Map.of("role",role,"content",content)));
            redisTemplate.opsForList().trim(key,-50,-1); redisTemplate.expire(key,SHORT_TTL);
        } catch(Exception e) { throw new IllegalStateException("Scoped memory write failed",e); }
    }

    public List<Map<String, String>> getShortTermMessages(Long userId, String sessionId) {
        // No fallback from a scoped run to legacy, unpartitioned private transcript.
        return List.of();
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
        // Legacy user-only recall is intentionally closed; callers must migrate to scoped recall.
        return List.of();
    }

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.nexus.chat.repository.agent.AgentSessionSummaryRepository summaries;

    public void clearShortTerm(Long userId, String sessionId) {
        if (summaries != null) summaries.deleteByUserIdAndSessionId(userId,sessionId);
        var keys=redisTemplate.keys(sessionPrefix(userId,sessionId)+"*");
        if(keys!=null&&!keys.isEmpty()) redisTemplate.delete(keys);
        // Pre-scope keys are never read again, but their transcript must still be removed.
        redisTemplate.delete(List.of("agent:ctx:" + userId + ":" + sessionId + ":messages",
                "agent:ctx:" + userId + ":" + sessionId, "agent:tool:" + userId + ":" + sessionId));
    }

    public record MemoryView(Long id,String content,Boolean confirmed,String sourceRunId,String sourceSessionId,java.time.LocalDateTime createdAt) {}
    public List<MemoryView> listSession(Long owner,String session) {
        return longMemoryRepository.findSessionActive(owner,session,org.springframework.data.domain.PageRequest.of(0,256)).stream()
                .map(m -> new MemoryView(m.getId(),m.getContent(),m.getConfirmed(),m.getSourceRunId(),m.getSourceSessionId(),m.getCreatedAt())).toList();
    }
    @org.springframework.transaction.annotation.Transactional
    public MemoryView confirmSessionMemory(Long owner,String session,Long id) {
        var row=longMemoryRepository.findById(id).filter(m -> owner.equals(m.getUserId()) && session.equals(m.getSourceSessionId()) && Boolean.TRUE.equals(m.getIsActive()))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Memory not found"));
        row.setConfirmed(true); longMemoryRepository.save(row);
        var audit=new AgentMemoryAudit();audit.setMemoryId(id);audit.setAction("CONFIRM");audit.setOperatorType("USER");audit.setOperatorId(owner);audit.setReason("explicit_confirmation");memoryAuditRepository.save(audit);
        return new MemoryView(row.getId(),row.getContent(),row.getConfirmed(),row.getSourceRunId(),session,row.getCreatedAt());
    }

    public void resetLongTerm(Long actor) {
        for(var memory:longMemoryRepository.findByUserIdAndIsActiveTrue(actor)) disableLongTerm(memory.getId(),actor,"user_reset");
    }

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.nexus.chat.repository.agent.AgentRunRecordRepository runRecords;

    @org.springframework.transaction.annotation.Transactional
    public void disableLongTerm(Long memoryId, Long operatorUserId, String reason) {
        AgentLongMemory memory = longMemoryRepository.findById(memoryId).filter(m->operatorUserId.equals(m.getUserId()))
                .orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
        if (memory.getSourceSessionId()!=null) {
            if(scopes!=null) scopes.revokeSession(operatorUserId,memory.getSourceSessionId());
            if(summaries!=null) summaries.deleteByUserIdAndSessionId(operatorUserId,memory.getSourceSessionId());
            if(runRecords!=null) runRecords.suppressMemorySource(operatorUserId,memory.getSourceSessionId(),memory.getSourceRunId());
        }
        memory.setIsActive(false); memory.setContent(""); memory.setEmbeddingVector(null); memory.setEmbeddingRevision(null);
        longMemoryRepository.save(memory);

        AgentMemoryAudit audit = new AgentMemoryAudit();
        audit.setMemoryId(memoryId);
        audit.setAction("DISABLE");
        audit.setOperatorType("USER");
        audit.setOperatorId(operatorUserId);
        audit.setReason(reason);
        memoryAuditRepository.save(audit);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.nexus.chat.service.agent.scope.AgentScopeService scopes;

    private void validate(com.nexus.chat.service.agent.scope.ScopeDtos.AuthorizedScope scope) {
        if (scopes == null || scope == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        scopes.validate(scope.actorUserId(), scope.sessionId(), scope.runId(), scope);
    }

    @org.springframework.transaction.annotation.Transactional
    public void writeLongTermMemoryIfNeeded(com.nexus.chat.service.agent.scope.ScopeDtos.AuthorizedScope scope,
            String type, String content, double confidence, String trace) {
        validate(scope); // Holds the binding row lock through commit: generation invalidation serializes with writes.
        if(confidence < .75 || content == null || content.isBlank()) return;
        var m = new AgentLongMemory(); m.setUserId(scope.actorUserId()); m.setScopeKey(scope.scopeKey());
        m.setSessionGeneration(scope.sessionGeneration()); m.setMemoryType(type); m.setContent(maskSensitive(content));
        m.setConfidence(BigDecimal.valueOf(confidence)); m.setSourceSessionId(scope.sessionId()); m.setSourceTraceId(trace); m.setIsActive(true);
        longMemoryRepository.saveAndFlush(m);
    }

    public List<AgentLongMemory> getLongTermTopK(com.nexus.chat.service.agent.scope.ScopeDtos.AuthorizedScope scope) {
        validate(scope);
        return longMemoryRepository.findTop8ByUserIdAndScopeKeyAndSessionGenerationAndIsActiveTrueOrderByUpdatedAtDesc(scope.actorUserId(),scope.scopeKey(),scope.sessionGeneration());
    }

    public List<Map<String,String>> getShortTermMessages(com.nexus.chat.service.agent.scope.ScopeDtos.AuthorizedScope scope) {
        validate(scope);
        var raw = redisTemplate.opsForList().range(messagesKey(scope),0,-1);
        if(raw==null) return List.of();
        return raw.stream().map(s->{try{return objectMapper.readValue(s,new TypeReference<Map<String,String>>(){});}catch(Exception e){return Map.<String,String>of();}}).toList();
    }

    /** Tombstone/generation must be committed before this best-effort physical purge. */
    public void purgeSession(Long actor, String session) {
        RuntimeException failure=null;
        try { clearShortTerm(actor,session); } catch(RuntimeException e) { failure=e; }
        try {
            for(var memory:longMemoryRepository.findByUserIdAndSourceSessionId(actor,session)) {
                memory.setIsActive(false); memory.setContent(""); memory.setEmbeddingVector(null); memory.setEmbeddingRevision(null); longMemoryRepository.save(memory);
            }
        } catch(RuntimeException e) { if(failure==null) failure=e; else failure.addSuppressed(e); }
        if(failure!=null) throw failure;
    }

    private String maskSensitive(String text) {
        return text
                .replaceAll("(1\\d{2})\\d{4}(\\d{4})", "$1****$2")
                .replaceAll("([a-zA-Z0-9._%+-])[a-zA-Z0-9._%+-]*@([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})", "$1***@$2");
    }
}
