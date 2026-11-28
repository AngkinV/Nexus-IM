package com.nexus.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import com.nexus.chat.service.agent.AgentProviderService;
import com.nexus.chat.service.agent.PythonModelGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** MySQL is authoritative. No shared server key, vector store or reply cache. */
@Service
@RequiredArgsConstructor
public class CompanionMemoryEngine {
    private final CompanionMemoryRepository memories;
    private final CompanionMessageRepository messages;
    private final TavernSessionRepository sessions;
    private final AgentProviderService providers;
    private final PythonModelGateway gateway;
    private final ObjectMapper mapper;
    public record Recall(List<Long> ids, List<String> lines, String status, long total, int indexed, String error) {}

    @Transactional
    public void capture(CompanionMessage source) {
        if (source == null || source.getId() == null || source.getSenderType() != CompanionMessage.SenderType.user
                || source.getUserId() == null || source.getRoleId() == null) return;
        long barrier=Optional.ofNullable(sessions.forgetBarrier(source.getUserId(),source.getRoleId())).orElse(0L);
        if (source.getId()<=barrier || Boolean.TRUE.equals(source.getMemoryProcessed())) return;
        String text = CompanionMemorySafety.candidate(source.getContent());
        if (text == null) { source.setMemoryProcessed(true); messages.save(source); return; }
        String hash = CompanionMemorySafety.hash("message:"+source.getId()+":"+text);
        // Includes forgotten rows: a retry must not resurrect a deleted fact.
        if (memories.existsByUserIdAndRoleIdAndSourceHash(source.getUserId(), source.getRoleId(), hash)) {
            source.setMemoryProcessed(true); messages.save(source); return;
        }
        CompanionMemory m = new CompanionMemory();
        m.setUserId(source.getUserId()); m.setRoleId(source.getRoleId()); m.setContent(text);
        m.setMemoryType(CompanionMemory.MemoryType.mid); m.setConfirmed(false); m.setShared(false);
        m.setSourceMessageId(source.getId()); m.setSourceSessionId(source.getSessionId());
        m.setSourceHash(hash); m.setSourceCreatedAt(source.getCreatedAt());
        memories.save(m);
        source.setMemoryProcessed(true); messages.save(source);
    }

    @Transactional
    public void recoverPending(Long owner,Long role) {
        long barrier=Optional.ofNullable(sessions.forgetBarrier(owner,role)).orElse(0L);
        for(var source:messages.findPendingMemory(owner,role,barrier,CompanionMessage.SenderType.user,PageRequest.of(0,100))) capture(source);
    }

    public Recall recall(Long owner, Long role, String query) {
        List<CompanionMemory> rows = new ArrayList<>(memories.findActive(owner, role, PageRequest.of(0, 128)));
        long total = memories.countActive(owner, role);
        String status = "unconfigured", failure = null;
        int indexed = 0;
        Map<Long, Double> scores = new HashMap<>();
        try {
            var selected = providers.resolveEmbeddingCredential(owner, null);
            if (selected.isPresent() && selected.get().defaultModel() != null && !selected.get().defaultModel().isBlank()) {
                var cfg = selected.get();
                String revision = fingerprint(cfg);
                status = "configured";
                if (query != null && !query.isBlank() && !rows.isEmpty()) {
                    List<CompanionMemory> stale = rows.stream().filter(m -> !revision.equals(m.getEmbeddingRevision())
                            || vector(m.getEmbeddingVector()).isEmpty()).limit(8).toList();
                    List<String> inputs = new ArrayList<>(); inputs.add(limit(query, 4000));
                    stale.forEach(m -> inputs.add(limit(m.getContent(), 4000)));
                    JsonNode result = gateway.embed(owner, cfg, inputs, 15);
                    if (result == null || !result.path("ok").asBoolean(false) || !result.path("vectors").isArray()
                            || result.path("vectors").size() != inputs.size()) {
                        status = "failed"; failure = result == null ? "INCOMPLETE" : result.at("/error/code").asText("EMBEDDING_FAILED");
                    } else if (!providers.resolveEmbeddingCredential(owner, null).map(c -> fingerprint(c).equals(revision)).orElse(false)) {
                        status = "rebuilding"; failure = "CONFIG_CHANGED";
                    } else {
                        List<Double> q = vector(result.path("vectors").get(0).toString());
                        if (q.isEmpty()) { status = "failed"; failure = "INVALID_VECTOR"; }
                        else {
                            for (int i = 0; i < stale.size(); i++) {
                                CompanionMemory old = stale.get(i);
                                JsonNode v = result.path("vectors").get(i + 1);
                                List<Double> parsed = vector(v.toString());
                                if (parsed.size() != q.size()) continue;
                                // Re-read after network I/O. Deleted or edited rows cannot be revived by a late embedding.
                                var live = memories.findByIdAndUserId(old.getId(), owner)
                                        .filter(m -> !Boolean.TRUE.equals(m.getDeleted()) && Objects.equals(m.getContent(), old.getContent()));
                                if (live.isEmpty()) continue;
                                live.get().setEmbeddingRevision(revision); live.get().setEmbeddingVector(v.toString());
                                memories.save(live.get()); old.setEmbeddingRevision(revision); old.setEmbeddingVector(v.toString());
                            }
                            for (CompanionMemory row : rows) {
                                if (!revision.equals(row.getEmbeddingRevision())) continue;
                                List<Double> v = vector(row.getEmbeddingVector());
                                if (v.size() == q.size()) { scores.put(row.getId(), cosine(q, v)); indexed++; }
                            }
                            status = indexed < rows.size() ? "rebuilding" : "ready";
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) { status = "failed"; failure = "EMBEDDING_UNAVAILABLE"; }
        String topic = Objects.toString(query, "").toLowerCase(Locale.ROOT);
        rows.removeIf(m -> m.getContent() == null || m.getContent().isBlank() || Boolean.TRUE.equals(m.getDeleted()));
        rows.sort(Comparator.<CompanionMemory>comparingDouble(m ->
                (Boolean.TRUE.equals(m.getConfirmed()) ? 2 : 0) + scores.getOrDefault(m.getId(), keyword(m.getContent(), topic))).reversed());
        List<Long> ids = new ArrayList<>(); List<String> lines = new ArrayList<>(); int budget = 0, confirmed = 0, candidates = 0;
        for (CompanionMemory row : rows) {
            boolean pinned = Boolean.TRUE.equals(row.getConfirmed());
            if (pinned ? confirmed >= 8 : candidates >= 4) continue;
            String text = row.getContent().strip();
            if (budget + text.length() > 1600) continue;
            var live = memories.findByIdAndUserId(row.getId(), owner);
            if (live.isEmpty() || Boolean.TRUE.equals(live.get().getDeleted()) || !Objects.equals(live.get().getContent(), row.getContent())) continue;
            budget += text.length(); if (pinned) confirmed++; else candidates++;
            ids.add(row.getId()); lines.add((pinned ? "已确认：" : "未确认候选（不是事实）：") + text + " [来源时间："+Objects.toString(row.getSourceCreatedAt(),"未记录")+"；发生时间："+Objects.toString(row.getOccurredAt(),"未注明，不得推定")+"]");
        }
        return new Recall(List.copyOf(ids), List.copyOf(lines), status, total, indexed, failure);
    }

    /** Recent raw turns plus a source-linked, extractive rolling summary. No extra model charge. */
    @Transactional
    public List<CompanionMessage> history(TavernSession session, int tokenBudget) {
        if (session == null) return List.of();
        long barrier = Optional.ofNullable(session.getSummaryResetThroughId()).orElse(0L);
        List<CompanionMessage> recent = new ArrayList<>(messages.findBySessionIdOrderByIdDesc(session.getId(), PageRequest.of(0, 32)));
        recent.removeIf(m -> m.getId() == null || m.getId() <= barrier);
        Collections.reverse(recent);
        int budget=Math.min(128000,Math.max(512,tokenBudget))*2;
        while(recent.size()>1 && recent.stream().mapToInt(m -> Objects.toString(m.getContent(),"").length()).sum()>budget) recent.remove(0);
        if (!recent.isEmpty()) {
            long before = recent.get(0).getId();
            long through = Math.max(barrier, Optional.ofNullable(session.getSummaryThroughId()).orElse(0L));
            List<CompanionMessage> old = messages.findSummarySource(session.getUserId(), session.getId(), through, before, PageRequest.of(0, 100));
            if (!old.isEmpty()) {
                StringBuilder summary = new StringBuilder(Objects.toString(session.getRollingSummary(), ""));
                for (CompanionMessage m : old) {
                    if (m.getSenderType() == CompanionMessage.SenderType.user && !CompanionMemorySafety.containsCredential(m.getContent())) {
                        summary.append("\n用户曾说（消息 ").append(m.getId()).append("；来源时间 ").append(Objects.toString(m.getCreatedAt(),"未记录，不得推定相对日期")).append("）：").append(limit(m.getContent(), 180));
                    }
                }
                // Only actual user-source excerpts, not invented model facts or a copied old persona's voice.
                String value = summary.toString();
                if (value.length() > 2400) value = value.substring(value.length() - 2400);
                session.setRollingSummary(value); session.setSummaryThroughId(old.get(old.size() - 1).getId());
                sessions.save(session);
            }
        }
        return recent;
    }

    @Transactional
    public void forget(Long owner, CompanionMemory row) {
        row.setDeleted(true); row.setContent(""); row.setEmbeddingVector(null); row.setEmbeddingRevision(null); memories.save(row);
        invalidate(owner, row.getRoleId());
    }
    @Transactional
    public CompanionMemory correct(Long owner,Long id,String content) {
        if(content==null || content.isBlank() || content.length()>4000 || CompanionMemorySafety.containsCredential(content))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Invalid memory correction");
        CompanionMemory previous=memories.findByIdAndUserId(id,owner).filter(m -> !Boolean.TRUE.equals(m.getDeleted()))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Memory not found"));
        Long role=previous.getRoleId();
        previous.setDeleted(true); previous.setContent(""); previous.setEmbeddingVector(null); previous.setEmbeddingRevision(null); memories.save(previous);
        CompanionMemory next=new CompanionMemory(); next.setUserId(owner);next.setRoleId(role);next.setMemoryType(previous.getMemoryType());
        next.setContent(content.strip());next.setConfirmed(true);next.setShared(false);next.setCorrectsId(previous.getId());
        memories.save(next); invalidate(owner,role); return next;
    }

    @Transactional
    public void forgetAll(Long owner, Long role) { memories.forgetAll(owner, role); invalidate(owner, role); }
    private void invalidate(Long owner, Long role) {
        Long barrier = messages.findLatestForRole(owner, role).map(CompanionMessage::getId).orElse(0L);
        // Archive is preserved; earlier raw context is quarantined to prevent forgotten facts being re-summarized.
        sessions.invalidateSummaries(owner, role, barrier);
    }
    private String fingerprint(AgentProviderService.ResolvedProvider c) {
        return com.nexus.chat.service.agent.memory.MemoryVectorSupport.revision(c);
    }
    private List<Double> vector(String raw) {
        if (raw == null || raw.length() > 262144) return List.of();
        try {
            JsonNode tree = mapper.readTree(raw);
            if (!tree.isArray() || tree.isEmpty() || tree.size() > 8192) return List.of();
            List<Double> values = new ArrayList<>();
            for (JsonNode n : tree) { if (!n.isNumber() || !Double.isFinite(n.doubleValue())) return List.of(); values.add(n.doubleValue()); }
            return values;
        } catch (Exception ignored) { return List.of(); }
    }
    static double cosine(List<Double> a, List<Double> b) {
        if (a.size() != b.size() || a.isEmpty()) return 0;
        return com.nexus.chat.service.agent.memory.MemoryVectorSupport.cosine(a,b);
    }
    static double keyword(String memory, String topic) {
        if (topic.isBlank()) return 0;
        String lower = memory.toLowerCase(Locale.ROOT); int hits = 0;
        for (int i=0; i+2<=topic.length(); i++) if (lower.contains(topic.substring(i,i+2))) hits++;
        return Math.min(.9, hits / 10.0);
    }
    private static String limit(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0,n); }
}
