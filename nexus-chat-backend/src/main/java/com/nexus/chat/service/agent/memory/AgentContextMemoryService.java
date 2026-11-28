package com.nexus.chat.service.agent.memory;

import com.fasterxml.jackson.databind.*;
import com.nexus.chat.model.agent.*;
import com.nexus.chat.repository.agent.*;
import com.nexus.chat.service.CompanionMemorySafety;
import com.nexus.chat.service.agent.scope.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** The signed invoke carries Java's durable history, not a second Python database. */
@Service
@RequiredArgsConstructor
public class AgentContextMemoryService {
    private final AgentRunRecordRepository runs;
    private final AgentSessionSummaryRepository summaries;
    private final AgentScopeService scopes;
    private final ObjectMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private AgentSemanticMemoryService semanticMemory;

    public Map<String,Object> context(ScopeDtos.AuthorizedScope scope) { return context(scope,null); }
    @Transactional
    public Map<String,Object> context(ScopeDtos.AuthorizedScope scope,String query) {
        scopes.validate(scope.actorUserId(), scope.sessionId(), scope.runId(), scope);
        List<AgentRunRecord> tail = new ArrayList<>(runs.findRecent(scope.actorUserId(),scope.sessionId(),PageRequest.of(0,128)));
        Collections.reverse(tail);
        List<Map<String,String>> history = new ArrayList<>();
        List<AgentRunRecord> eligible = tail.stream().filter(r -> "done".equals(r.getStatus()) && !Boolean.TRUE.equals(r.getMemorySuppressed()) && matches(r,scope)).toList();
        int first = Math.max(0,eligible.size()-6);
        for (int i=first;i<eligible.size();i++) {
            JsonNode p=parse(eligible.get(i));
            add(history,"user",p.path("input").asText(""));
            if (p.path("error").isNull() || p.path("error").isMissingNode()) add(history,"assistant",p.path("answer").asText(""));
        }
        var current=summaries.findTopByUserIdAndSessionIdAndScopeKeyAndSessionGenerationOrderBySummaryVersionDesc(scope.actorUserId(),scope.sessionId(),scope.scopeKey(),scope.sessionGeneration());
        String summary=current.map(AgentSessionSummary::getSummaryText).orElse("");
        long through=current.map(AgentSessionSummary::getCoveredToMsgId).orElse(0L);
        if (first>0) {
            long before=eligible.get(first).getId();
            List<AgentRunRecord> older=runs.findSummarySource(scope.actorUserId(),scope.sessionId(),through,before,PageRequest.of(0,128));
            if (!older.isEmpty()) {
                StringBuilder text=new StringBuilder(summary);
                for (AgentRunRecord r:older) {
                    if (!"done".equals(r.getStatus()) || Boolean.TRUE.equals(r.getMemorySuppressed()) || !matches(r,scope)) continue;
                    JsonNode p=parse(r);
                    String input=p.path("input").asText("");
                    if (!input.isBlank() && !CompanionMemorySafety.containsCredential(input)) text.append("\n用户输入（执行 ").append(r.getId()).append("）：").append(limit(input,180));
                    String answer=p.path("answer").asText("");
                    if (!answer.isBlank() && !CompanionMemorySafety.containsCredential(answer)) text.append("\n助手回复（执行 ").append(r.getId()).append("，仅历史记录）：").append(limit(answer,240));
                }
                summary=text.toString(); if(summary.length()>3200) summary=summary.substring(summary.length()-3200);
                AgentSessionSummary next=new AgentSessionSummary();
                next.setUserId(scope.actorUserId()); next.setSessionId(scope.sessionId()); next.setScopeKey(scope.scopeKey()); next.setSessionGeneration(scope.sessionGeneration());
                next.setSummaryVersion(summaries.findTopByUserIdAndSessionIdOrderBySummaryVersionDesc(scope.actorUserId(),scope.sessionId()).map(s -> s.getSummaryVersion()+1).orElse(1));
                next.setCoveredFromMsgId(older.get(0).getId()); through=older.get(older.size()-1).getId();
                next.setCoveredToMsgId(through); next.setSummaryText(summary); summaries.save(next);
            }
        }
        Map<String,Object> result=new LinkedHashMap<>(Map.of("scopeKey",scope.scopeKey(),"sessionGeneration",scope.sessionGeneration(),"messages",history,
                "summary",summary,"summaryThrough",through,"source","mysql"));
        if(semanticMemory!=null) {
            semanticMemory.recover(scope);
            var recalled=semanticMemory.recall(scope,query);
            result.put("recalled",recalled.lines());result.put("memoryIds",recalled.ids());
            result.put("memoryStatus",recalled.status());result.put("memoryIndexed",recalled.indexed());
            result.put("memoryError",Objects.toString(recalled.error(),""));
        }
        return result;
    }
    private boolean matches(AgentRunRecord r,ScopeDtos.AuthorizedScope s) {
        JsonNode p=parse(r); JsonNode saved=p.path("scope");
        return s.scopeKey().equals(saved.path("scopeKey").asText()) && s.sessionGeneration()==saved.path("sessionGeneration").asLong()
                && s.actorUserId()==saved.path("actorUserId").asLong() && s.sessionId().equals(saved.path("sessionId").asText());
    }
    private JsonNode parse(AgentRunRecord r) {
        try { return mapper.readTree(r.getPayloadJson()); }
        catch(Exception ignored) { return mapper.createObjectNode(); }
    }
    private static void add(List<Map<String,String>> out,String role,String text) {
        if(!text.isBlank()&&!CompanionMemorySafety.containsCredential(text)) out.add(Map.of("role",role,"content",limit(text,6000)));
    }
    private static String limit(String s,int n) { return s.length()<=n?s:s.substring(0,n); }
}
