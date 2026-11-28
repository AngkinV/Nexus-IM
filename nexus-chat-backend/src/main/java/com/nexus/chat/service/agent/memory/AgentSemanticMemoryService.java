package com.nexus.chat.service.agent.memory;

import com.fasterxml.jackson.databind.*;
import com.nexus.chat.model.agent.*;
import com.nexus.chat.repository.agent.*;
import com.nexus.chat.service.CompanionMemorySafety;
import com.nexus.chat.service.agent.*;
import com.nexus.chat.service.agent.scope.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

/** Workbench memory reuses the business table and the same explicit account embedding gateway. */
@Service
@RequiredArgsConstructor
public class AgentSemanticMemoryService {
    private final AgentLongMemoryRepository memories;
    private final AgentRunRecordRepository runs;
    private final AgentMemoryAuditRepository audits;
    private final AgentScopeService scopes;
    private final AgentProviderService providers;
    private final PythonModelGateway gateway;
    private final ObjectMapper mapper;
    public record Recall(List<String> lines,List<Long> ids,String status,int indexed,String error) {}

    @Transactional
    public void recover(ScopeDtos.AuthorizedScope scope) {
        scopes.validate(scope.actorUserId(),scope.sessionId(),scope.runId(),scope);
        for(AgentRunRecord r:runs.findPendingMemory(scope.actorUserId(),scope.sessionId(),PageRequest.of(0,100))) {
            JsonNode p=parse(r);
            JsonNode old=p.path("scope");
            if (!scope.scopeKey().equals(old.path("scopeKey").asText()) || scope.sessionGeneration()!=old.path("sessionGeneration").asLong()) continue;
            String content=CompanionMemorySafety.candidate(p.path("input").asText(""));
            if (content!=null) {
                String hash=CompanionMemorySafety.hash(content);
                if (!memories.existsByUserIdAndScopeKeyAndSourceHash(scope.actorUserId(),scope.scopeKey(),hash)) {
                    AgentLongMemory m=new AgentLongMemory(); m.setUserId(scope.actorUserId());m.setScopeKey(scope.scopeKey());m.setSessionGeneration(scope.sessionGeneration());
                    m.setMemoryType("PREFERENCE");m.setContent(content);m.setConfirmed(false);m.setIsActive(true);m.setConfidence(new BigDecimal("0.750"));
                    m.setSourceSessionId(scope.sessionId());m.setSourceRunId(r.getRunId());m.setSourceCreatedAt(r.getStartedAt());m.setSourceTraceId(r.getRunId());m.setSourceHash(hash);
                    memories.saveAndFlush(m);
                    AgentMemoryAudit a=new AgentMemoryAudit();a.setMemoryId(m.getId());a.setAction("CREATE");a.setOperatorType("SYSTEM");a.setOperatorId(scope.actorUserId());a.setReason("source_user_candidate");audits.save(a);
                }
            }
            runs.markMemoryProcessed(r.getId());
        }
    }

    public Recall recall(ScopeDtos.AuthorizedScope scope,String query) {
        scopes.validate(scope.actorUserId(),scope.sessionId(),scope.runId(),scope);
        List<AgentLongMemory> rows=new ArrayList<>(memories.findRecall(scope.actorUserId(),scope.sessionId(),scope.scopeKey(),scope.sessionGeneration(),PageRequest.of(0,128)));
        rows.removeIf(m -> m.getContent()==null || m.getContent().isBlank() || !Boolean.TRUE.equals(m.getIsActive()));
        String status="unconfigured",error=null;int indexed=0;Map<Long,Double> scores=new HashMap<>();
        try {
            var optional=providers.resolveEmbeddingCredential(scope.actorUserId(),null);
            if(optional.isPresent() && optional.get().defaultModel()!=null && !optional.get().defaultModel().isBlank()) {
                var cfg=optional.get();String revision=MemoryVectorSupport.revision(cfg);status="configured";
                if(query!=null&&!query.isBlank()&&!rows.isEmpty()) {
                    List<AgentLongMemory> stale=rows.stream().filter(m -> !revision.equals(m.getEmbeddingRevision()) || MemoryVectorSupport.decode(mapper,m.getEmbeddingVector()).isEmpty()).limit(8).toList();
                    List<String> inputs=new ArrayList<>();inputs.add(limit(query,4000));stale.forEach(m -> inputs.add(limit(m.getContent(),4000)));
                    JsonNode result=gateway.embed(scope.actorUserId(),cfg,inputs,15);
                    if(result==null || !result.path("ok").asBoolean() || !result.path("vectors").isArray() || result.path("vectors").size()!=inputs.size()) {
                        status="failed";error=result==null?"INCOMPLETE":result.at("/error/code").asText("EMBEDDING_FAILED");
                    } else if (!providers.resolveEmbeddingCredential(scope.actorUserId(),null).map(c -> revision.equals(MemoryVectorSupport.revision(c))).orElse(false)) {
                        status="rebuilding";error="CONFIG_CHANGED";
                    } else {
                        var q=MemoryVectorSupport.decode(mapper,result.path("vectors").get(0).toString());
                        if(q.isEmpty()) { status="failed";error="INVALID_VECTOR"; }
                        else {
                            for(int i=0;i<stale.size();i++) {
                                var row=stale.get(i);String encoded=result.path("vectors").get(i+1).toString();
                                if(MemoryVectorSupport.decode(mapper,encoded).size()!=q.size()) continue;
                                var live=memories.findById(row.getId()).filter(m -> scope.actorUserId().equals(m.getUserId()) && Boolean.TRUE.equals(m.getIsActive()) && Objects.equals(row.getContent(),m.getContent()));
                                if(live.isEmpty()) continue;
                                live.get().setEmbeddingRevision(revision);live.get().setEmbeddingVector(encoded);memories.save(live.get());
                                row.setEmbeddingRevision(revision);row.setEmbeddingVector(encoded);
                            }
                            for(var row:rows) if(revision.equals(row.getEmbeddingRevision())) {
                                var v=MemoryVectorSupport.decode(mapper,row.getEmbeddingVector());
                                if(v.size()==q.size()) { indexed++;scores.put(row.getId(),MemoryVectorSupport.cosine(q,v)); }
                            }
                            status=indexed<rows.size()?"rebuilding":"ready";
                        }
                    }
                }
            }
        } catch(RuntimeException ignored) { status="failed";error="EMBEDDING_UNAVAILABLE"; }
        rows.sort(Comparator.<AgentLongMemory>comparingDouble(m -> (Boolean.TRUE.equals(m.getConfirmed())?2:0)+scores.getOrDefault(m.getId(),MemoryVectorSupport.keyword(m.getContent(),query))).reversed());
        List<String> lines=new ArrayList<>();List<Long> ids=new ArrayList<>();int used=0;
        for(var row:rows) {
            if(ids.size()>=8 || used+row.getContent().length()>1400) continue;
            var live=memories.findById(row.getId());
            if(live.isEmpty() || !Boolean.TRUE.equals(live.get().getIsActive()) || !scope.actorUserId().equals(live.get().getUserId())) continue;
            ids.add(row.getId());used+=row.getContent().length();lines.add((Boolean.TRUE.equals(row.getConfirmed())?"已确认偏好：":"未确认候选（不是事实）：")+row.getContent()+" [来源记录时间："+Objects.toString(row.getSourceCreatedAt(),"未记录")+"；发生时间未注明，不得推定]");
        }
        return new Recall(List.copyOf(lines),List.copyOf(ids),status,indexed,error);
    }
    private JsonNode parse(AgentRunRecord r) { try{return mapper.readTree(r.getPayloadJson());}catch(Exception ignored){return mapper.createObjectNode();} }
    private String limit(String s,int n) { return s.length()<=n?s:s.substring(0,n); }
}
