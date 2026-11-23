package com.nexus.chat.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.dto.agent.AgentSessionAndChatDtos;
import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.model.agent.AgentRunRecord;
import com.nexus.chat.repository.agent.AgentRunRecordRepository;
import com.nexus.chat.repository.agent.AgentSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Durable record of each agent run: what was asked, what the model produced, and
 * which tools, sources and artifacts it used.
 *
 * The record is the history the workbench restores, so it holds only what the
 * client was shown. Credentials, internal headers and attachment bodies never
 * enter it — attachments are referenced by ID, and their content lives in the
 * workspace.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunService {

    /** Event names the client is allowed to see; anything else is dropped unread. */
    static final Set<String> PUBLIC_EVENTS = Set.of(
            "meta", "stage", "reasoning", "agent_output", "tool_call", "tool_result",
            "sources", "artifact", "delta", "usage", "done", "error");

    /** Keys that would carry a credential or a raw header; removed wherever they appear. */
    private static final Set<String> SECRET_KEYS = Set.of(
            "apikey", "api_key", "authorization", "headers", "internaltoken", "internal_token",
            "signingsecret", "signing_secret", "x-model-api-key", "secret", "password", "token");

    private static final int TEXT_SAVE_INTERVAL = 20;
    private static final int TEXT_SAVE_MILLIS = 400;

    private final ObjectMapper mapper;
    private final AgentRunRecordRepository runs;
    private final AgentSessionRepository sessions;
    @org.springframework.beans.factory.annotation.Autowired
    private com.nexus.chat.service.agent.scope.AgentScopeService scopes;

    private void validateScope(Long actor, String session, String run, Map<String,Object> payload) {
        if (scopes == null) throw new AgentException(AgentErrorCode.AGENT_AUTHZ_40301, "Scope authority unavailable");
        scopes.validate(actor,session,run,mapper.convertValue(payload.get("scope"),com.nexus.chat.service.agent.scope.ScopeDtos.AuthorizedScope.class));
    }

    /**
     * Private run output needs a live grant. A run recorded before T2 has no scope
     * at all, so nothing can be re-confirmed for it: it is refused like any other
     * run without a live grant (the name is kept for its existing callers).
     */
    private void validateScopeOrLegacy(Long actor, String session, String run, Map<String,Object> payload) {
        // Artifacts are private output too: a run without a scope cannot hand them back.
        validateScope(actor, session, run, payload);
    }

    /**
     * Payloads not yet written. Text arrives faster than it should be stored, so the
     * working copy lives here between saves and is what the next event and the
     * finish step build on.
     */
    private final Map<String, Map<String, Object>> pending = new java.util.concurrent.ConcurrentHashMap<>();

    // Fixed stripes never disappear while another handler is waiting.
    private final Object[] locks = java.util.stream.IntStream.range(0, 256)
            .mapToObj(i -> new Object()).toArray();
    private final Map<String, String> writers = new java.util.concurrent.ConcurrentHashMap<>();
    private Object lock(String runId) { return locks[runId == null ? 0 : (runId.hashCode() & 0x7fffffff) % locks.length]; }

    /** Opaque Java handler lease; never sent on the wire or stored in the payload. */
    public String beginWriter(Long userId, String sessionId, String runId,
                              AgentSessionAndChatDtos.SessionChatRequest request) {
        synchronized (lock(runId)) { begin(userId, sessionId, runId, request); return writers.get(runId); }
    }
    public String resumeWriter(Long userId, String sessionId, String runId, String traceId) {
        synchronized (lock(runId)) {
            Map<String, Object> result = resume(userId, sessionId, runId, traceId);
            return isSealed(result) ? null : writers.get(runId);
        }
    }
    /** A resume that was accepted: the writer lease (null when the outcome is only handed back) and its fresh receipt. */
    public record ResumeLease(String generation, com.nexus.chat.service.agent.scope.ScopeDtos.AuthorizedScope scope) {}

    /**
     * Accept the resume first, then rotate the grant. A request that is refused because the
     * run is already being resumed never reaches {@code authorize}, so it cannot revoke the
     * grant the attempt in flight is running under.
     */
    public ResumeLease resumeWriter(Long userId, String sessionId, String runId, String traceId,
            java.util.function.Supplier<com.nexus.chat.service.agent.scope.ScopeDtos.AuthorizedScope> authorize) {
        synchronized (lock(runId)) {
            Map<String, Object> payload = workingCopy(runId, owned(userId, sessionId, runId));
            if (!isSealed(payload) && Boolean.TRUE.equals(payload.get("resuming"))) {
                throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "This run is already being resumed");
            }
            var scope = authorize.get();
            replaceScope(userId, sessionId, runId, scope);
            return new ResumeLease(resumeWriter(userId, sessionId, runId, traceId), scope);
        }
    }
    /** Production handlers must use this overload; legacy unleased callers remain compatible. */
    public void record(Long userId, String sessionId, String runId, String generation,
                       String event, Map<String, Object> data) {
        synchronized (lock(runId)) {
            owned(userId, sessionId, runId);
            if (generation != null && generation.equals(writers.get(runId))) record(userId, sessionId, runId, event, data);
        }
    }
    public Map<String, Object> finish(Long userId, String sessionId, String runId, String generation) {
        synchronized (lock(runId)) {
            if (generation == null || !generation.equals(writers.get(runId))) return view(owned(userId, sessionId, runId));
            return finish(userId, sessionId, runId);
        }
    }

    // ----- lifecycle -----

    public Map<String, Object> begin(Long userId, String sessionId, String runId,
                                     AgentSessionAndChatDtos.SessionChatRequest request) {
        synchronized (lock(runId)) {
            ownedSession(userId, sessionId);
            if (runId == null || !runId.matches("[A-Za-z0-9_-]{8,64}")) {
                throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Invalid run id");
            }
            if (runs.existsByRunId(runId)) {
                throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Run id is already in use");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", runId);
            payload.put("scope", request == null || request.getAuthorizedScope() == null ? null : mapper.convertValue(request.getAuthorizedScope(), Map.class));
            payload.put("scopeSelection", request == null ? null : request.getScopeSelection());
            payload.put("expectedBindingVersion", request == null || request.getAuthorizedScope() == null ? null : request.getAuthorizedScope().bindingVersion());
            payload.put("input", request == null ? "" : request.getInput());
            payload.put("operationType", request == null || request.getOperationType() == null
                    ? "ASSISTANT_CHAT" : request.getOperationType().name());
            payload.put("providerRef", request == null || request.getProviderId() == null
                    ? Map.of() : Map.of("providerId", request.getProviderId()));
            payload.put("options", optionsOf(request));
            payload.put("attachmentRefs", attachmentRefs(request));
            payload.put("retryOfRunId", request == null ? null : request.getRetryOfRunId());
            payload.put("answer", "");
            payload.put("reasoning", "");
            payload.put("trace", new ArrayList<Map<String, Object>>());
            payload.put("toolCalls", new ArrayList<Map<String, Object>>());
            payload.put("sources", new ArrayList<Map<String, Object>>());
            payload.put("artifacts", new ArrayList<Map<String, Object>>());
            payload.put("status", "running");

            AgentRunRecord record = new AgentRunRecord();
            record.setUserId(userId);
            record.setSessionId(sessionId);
            record.setRunId(runId);
            record.setStatus("running");
            record.setStartedAt(Instant.now());
            record.setUpdatedAt(record.getStartedAt());
            record.setPayloadJson(write(payload));
            runs.saveAndFlush(record);
            pending.put(runId, payload);
            writers.put(runId, java.util.UUID.randomUUID().toString());
            return view(record);
        }
    }

    /**
     * Record one public event. Unknown events and mismatched run ids are refused
     * rather than written, so a stream can never extend someone else's run.
     */
    public void record(Long userId, String sessionId, String runId, String eventName, Map<String, Object> data) {
        synchronized (lock(runId)) {
            if (!PUBLIC_EVENTS.contains(eventName)) {
                return;
            }
            AgentRunRecord record = owned(userId, sessionId, runId);
            Map<String, Object> payload = workingCopy(runId, record);
            Object declared = data == null ? null : data.get("runId");
            if (declared != null && !runId.equals(declared)) {
                throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Event belongs to another run");
            }
            Map<String, Object> clean = sanitize(data == null ? Map.of() : data);
            List<Map<String, Object>> trace = list(payload, "trace");
            // Sequence numbers are local to one attempt, never to the whole run or an event name.
            String attemptKey = eventAttempt(payload, eventName, clean);
            Object sequence = clean.get("sequence");
            if (sequence != null && trace.stream().anyMatch(frame -> {
                Object inner = frame.get("data");
                return inner instanceof Map<?, ?> fields && sequence.equals(fields.get("sequence"))
                        && attemptKey.equals(frameAttempt(frame));
            })) return;
            String previous = text(payload.get("eventAttempt"));
            boolean knownAttempt = attemptKey.equals(previous)
                    || list(payload, "retiredAttempts").stream().anyMatch(item -> attemptKey.equals(item.get("id")));
            boolean resuming = Boolean.TRUE.equals(payload.get("resuming"));
            boolean approved = "meta".equals(eventName)
                    && (clean.get("traceId") == null || java.util.Objects.equals(clean.get("traceId"), payload.get("resumeTraceId")));
            // Before meta, early terminal/legacy frames may bind an unseen attempt, but
            // an archived identity can never seize a newly requested generation.
            boolean early = ("error".equals(eventName) || "done".equals(eventName)
                    || !clean.containsKey("attemptId"))
                    && (clean.get("traceId") == null || java.util.Objects.equals(clean.get("traceId"), payload.get("resumeTraceId")));
            boolean selectAttempt = previous.isEmpty() || (!attemptKey.equals(previous)
                    && !knownAttempt && resuming && (approved || early));
            if (selectAttempt) {
                payload.put("eventAttempt", attemptKey);
                payload.put("resuming", false);
                if (!previous.isEmpty()) {
                    payload.remove("error"); payload.remove("finishReason"); payload.remove("terminalAttempt");
                    payload.remove("deliveryMode");
                    payload.put("status", "running");
                }
            }
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("name", eventName);
            frame.put("data", clean);
            frame.put("attemptKey", attemptKey); // Explicit legacy boundary; not a fabricated wire frame.
            trace.add(frame);
            boolean current = attemptKey.equals(payload.get("eventAttempt")) && (!resuming || selectAttempt);
            boolean ended = attemptKey.equals(payload.get("terminalAttempt"));
            if (current && !ended) observeElapsed(payload, eventName, clean, attemptKey);
            if (current && (!ended || ("error".equals(eventName) && !"run".equals(errorScope(clean))))) {
                apply(payload, eventName, clean);
            }
            record.setPayloadJson(write(payload));
            record.setStatus((String) payload.get("status"));
            record.setUpdatedAt(Instant.now());
            pending.put(runId, payload);
            if (shouldSaveNow(eventName, trace.size())) {
                runs.saveAndFlush(record);
            }
        }
    }

    /** Close the run. A stream that ended without a terminal event is interrupted, not done. */
    public Map<String, Object> finish(Long userId, String sessionId, String runId) {
        synchronized (lock(runId)) {
            AgentRunRecord record = owned(userId, sessionId, runId);
            Map<String, Object> payload = workingCopy(runId, record);
            String status = (String) payload.get("status");
            if ("running".equals(status)) {
                status = "interrupted";
            }
            payload.put("status", status);
            Instant finished = Instant.now();
            record.setStatus(status);
            record.setFinishedAt(finished);
            record.setUpdatedAt(finished);
            record.setPayloadJson(write(payload));
            runs.saveAndFlush(record);
            pending.remove(runId);
            writers.remove(runId);
            return view(record);
        }
    }

    /** Confirm a resume: only the owner, only a run that stopped before it finished. */
    public Map<String, Object> resume(Long userId, String sessionId, String runId, String traceId) {
        synchronized (lock(runId)) {
            AgentRunRecord record = owned(userId, sessionId, runId);
            Map<String, Object> payload = workingCopy(runId, record);
            // A finished run is not resumed: the agent service hands back what it produced,
            // which is exactly what a client that missed `done` needs. The record stays as it
            // is, and nothing is written twice.
            if (isSealed(payload)) {
                return view(record);
            }
            if (Boolean.TRUE.equals(payload.get("resuming"))) {
                throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "This run is already being resumed");
            }
            // Everything observed before this request belongs to an older generation.
            for (Map<String, Object> frame : list(payload, "trace")) {
                mergeById(list(payload, "retiredAttempts"), List.of(Map.of("id", frameAttempt(frame))));
            }
            String previousAttempt = text(payload.get("eventAttempt"));
            if (!previousAttempt.isEmpty()) mergeById(list(payload, "retiredAttempts"), List.of(Map.of("id", previousAttempt)));
            payload.put("resuming", true);
            payload.put("resumeTraceId", traceId);
            // Each confirmed resume is a new attempt: the trace keeps the old one, the
            // reasoning and answer of the new one start over.
            Object attempt = payload.get("attempt");
            payload.put("attempt", attempt instanceof Number number ? number.intValue() + 1 : 2);
            payload.put("status", "running");
            record.setStatus("running");
            record.setFinishedAt(null);
            record.setPayloadJson(write(payload));
            record.setUpdatedAt(Instant.now());
            runs.saveAndFlush(record);
            pending.put(runId, payload);
            writers.put(runId, java.util.UUID.randomUUID().toString());
            return view(record);
        }
    }

    /**
     * Whether the outcome can only be handed back. A failed or cancelled attempt whose
     * terminal frame said the checkpoint was kept is continued instead, and that
     * continuation has to be recorded like any other attempt.
     */
    private static boolean isSealed(Map<String, Object> run) {
        Object status = run.get("status");
        if ("done".equals(status) || "truncated".equals(status)) return true;
        return ("error".equals(status) || "cancelled".equals(status)) && !Boolean.TRUE.equals(run.get("resumable"));
    }

    /** Store the fresh receipt a resume or retry was authorized under; the original selection is kept. */
    public void replaceScope(Long userId, String sessionId, String runId, com.nexus.chat.service.agent.scope.ScopeDtos.AuthorizedScope scope) {
        synchronized (lock(runId)) {
            var record = owned(userId, sessionId, runId);
            var payload = workingCopy(runId, record);
            payload.put("scope", mapper.convertValue(scope, Map.class));
            payload.put("expectedBindingVersion", scope.bindingVersion());
            record.setPayloadJson(write(payload));
            runs.saveAndFlush(record);
            pending.put(runId, payload);
        }
    }

    /** The payload as this process knows it: unsaved changes included. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> workingCopy(String runId, AgentRunRecord record) {
        Map<String, Object> stored = read(record.getPayloadJson());
        Map<String, Object> live = pending.get(runId);
        if (live == null) return stored;
        // The live payload is authoritative until its buffered events are flushed.
        return live;
    }

    // ----- reads -----

    public List<Map<String, Object>> list(Long userId, String sessionId) {
        ownedSession(userId, sessionId);
        return runs.findByUserIdAndSessionIdOrderByStartedAtAscIdAsc(userId, sessionId).stream()
                .map(this::view).toList();
    }

    /** The canonical artifact of this run, as an independent copy the caller may mutate. */
    public Map<String, Object> findArtifact(Long userId, String sessionId, String artifactId) {
        ownedSession(userId, sessionId);
        for (AgentRunRecord record : runs.findByUserIdAndSessionIdOrderByStartedAtAscIdAsc(userId, sessionId)) {
            Map<String, Object> current = read(write(workingCopy(record.getRunId(), record)));
            validateScopeOrLegacy(userId, sessionId, record.getRunId(), current);
            for (Map<String, Object> artifact : list(current, "artifacts")) {
                if (artifactId.equals(artifact.get("id"))) {
                    return new LinkedHashMap<>(artifact);
                }
            }
        }
        throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Artifact not found");
    }

    public void deleteSession(Long userId, String sessionId) {
        ownedSession(userId, sessionId);
        runs.findByUserIdAndSessionIdOrderByStartedAtAscIdAsc(userId, sessionId)
                .forEach(record -> {
                    synchronized (lock(record.getRunId())) {
                        pending.remove(record.getRunId());
                        writers.remove(record.getRunId());
                    }
                });
        runs.deleteByUserIdAndSessionId(userId, sessionId);
    }

    // ----- projection -----

    private static String eventAttempt(Map<String, Object> payload, String event, Map<String, Object> data) {
        if (data.get("attemptId") instanceof String id && !id.isBlank()) return id;
        if ("meta".equals(event) && data.get("traceId") instanceof String trace && !trace.isBlank()) {
            return "legacy:" + trace;
        }
        if (Boolean.TRUE.equals(payload.get("resuming")) && payload.get("resumeTraceId") != null) {
            return "legacy:" + payload.get("resumeTraceId");
        }
        return payload.get("eventAttempt") instanceof String id ? id : "legacy:initial";
    }

    private static String frameAttempt(Map<String, Object> frame) {
        if (frame.get("attemptKey") instanceof String key) return key;
        if (frame.get("data") instanceof Map<?, ?> data && data.get("attemptId") instanceof String id) return id;
        return "legacy:initial";
    }

    static String errorScope(Map<String, Object> data) {
        if (data.get("scope") instanceof String scope && Set.of("run", "agent", "tool", "transport").contains(scope)) {
            return scope;
        }
        if (Boolean.TRUE.equals(data.get("transport"))) return "transport";
        if (data.get("callId") != null) return "tool";
        Object agent = data.get("agentId");
        return agent != null && !"assistant".equals(agent) && !"synth".equals(agent) ? "agent" : "run";
    }

    @SuppressWarnings("unchecked")
    private static void observeElapsed(Map<String, Object> payload, String event, Map<String, Object> data, String attemptKey) {
        if ("meta".equals(event)) payload.put("deliveryMode", data.get("deliveryMode"));
        if ("handover".equals(payload.get("deliveryMode"))) return;
        if (!(data.get("at") instanceof Number at) || at.longValue() < 0) return;
        Map<String, Object> elapsed = (Map<String, Object>) payload.computeIfAbsent("attemptElapsed", key -> new LinkedHashMap<>());
        Object previous = elapsed.get(attemptKey);
        long value = previous instanceof Number number ? Math.max(number.longValue(), at.longValue()) : at.longValue();
        elapsed.put(attemptKey, value);
        payload.put("activeElapsedMs", elapsed.values().stream().filter(Number.class::isInstance)
                .mapToLong(item -> ((Number) item).longValue()).sum());
    }

    private static void statistics(Map<String, Object> payload, Map<String, Object> data) {
        for (String key : List.of("modelCalls", "toolCallCount", "reusedToolCallCount", "usageReported", "usageComplete", "usageScope")) {
            if (data.containsKey(key)) payload.put(key, data.get(key));
        }
    }

    private void apply(Map<String, Object> payload, String event, Map<String, Object> data) {
        switch (event) {
            case "delta" -> {
                String text = text(data.get("text"));
                if (Boolean.TRUE.equals(data.get("replayed"))
                        || !java.util.Objects.equals(payload.get("answerAttempt"), payload.get("eventAttempt"))) {
                    payload.put("answer", text);
                } else {
                    payload.put("answer", text(payload.get("answer")) + text);
                }
                payload.put("answerAttempt", payload.get("eventAttempt"));
            }
            case "reasoning" -> {
                // A resumed attempt starts a fresh transcript; the previous one stays in the trace.
                // The replay frame may already have cleared the flag, so the attempt counter decides.
                boolean freshAttempt = Boolean.TRUE.equals(payload.get("resuming"))
                        || !java.util.Objects.equals(payload.get("reasoningAttempt"), payload.get("attempt"));
                if (freshAttempt) {
                    payload.put("reasoning", text(data.get("text")));
                    payload.put("reasoningAttempt", payload.get("attempt"));
                    payload.put("resuming", false);
                } else {
                    payload.put("reasoning", text(payload.get("reasoning")) + text(data.get("text")));
                }
            }
            case "agent_output" -> list(payload, "agentOutputs").add(data);
            case "tool_call" -> {
                Map<String, Object> call = new LinkedHashMap<>(data);
                call.putIfAbsent("status", "running");
                list(payload, "toolCalls").add(call);
            }
            case "tool_result" -> mergeCall(list(payload, "toolCalls"), data);
            case "sources" -> mergeById(list(payload, "sources"), data.get("items"));
            case "artifact" -> {
                Object artifact = data.get("artifact");
                if (artifact instanceof Map<?, ?> map) {
                    mergeById(list(payload, "artifacts"), List.of(map));
                }
            }
            case "usage" -> {
                payload.put("usage", data);
                statistics(payload, data);
            }
            case "done" -> {
                // A stop frame cannot upgrade a run-wide failure; local diagnostics are not run errors.
                payload.put("status", payload.get("error") == null ? statusOf(data.get("finishReason")) : "error");
                payload.put("finishReason", data.get("finishReason"));
                if (data.containsKey("resumable")) payload.put("resumable", data.get("resumable"));
                payload.put("terminalAttempt", payload.get("eventAttempt"));
                statistics(payload, data);
            }
            case "error" -> {
                String scope = errorScope(data);
                if ("run".equals(scope)) {
                    if (payload.get("error") == null) payload.put("error", data);
                    payload.put("status", "error");
                } else {
                    list(payload, "diagnostics").add(data);
                }
            }
            case "meta" -> {
                payload.put("provider", data.get("provider"));
                payload.put("model", data.get("model"));
                if (data.get("executionMode") != null) payload.put("executionMode", data.get("executionMode"));
                if (Boolean.TRUE.equals(data.get("resumed"))) payload.put("resuming", false);
                // The resume request's trace is the one that produced the rest of this run.
                if (data.get("traceId") != null) payload.put("traceId", data.get("traceId"));
            }
            default -> { }
        }
    }

    private static void mergeCall(List<Map<String, Object>> calls, Map<String, Object> data) {
        Object callId = data.get("callId");
        for (int index = calls.size() - 1; index >= 0; index--) {
            Map<String, Object> call = calls.get(index);
            boolean same = callId != null ? callId.equals(call.get("callId"))
                    : data.get("toolName") != null && data.get("toolName").equals(call.get("toolName"));
            for (String identity : List.of("agentId", "nodeId")) {
                if (data.get(identity) != null && call.get(identity) != null
                        && !data.get(identity).equals(call.get(identity))) same = false;
            }
            if (!Boolean.TRUE.equals(data.get("reused")) && data.get("attemptId") != null
                    && call.get("attemptId") != null && !data.get("attemptId").equals(call.get("attemptId"))) same = false;
            if (same) {
                call.putAll(data);
                return;
            }
        }
        calls.add(new LinkedHashMap<>(data));
    }

    private static void mergeById(List<Map<String, Object>> items, Object incoming) {
        if (!(incoming instanceof List<?> list)) return;
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map) || map.get("id") == null) continue;
            String id = String.valueOf(map.get("id"));
            items.removeIf(existing -> id.equals(existing.get("id")));
            items.add(new LinkedHashMap<>((Map<String, Object>) map));
        }
    }

    private static String statusOf(Object finishReason) {
        String reason = finishReason == null ? "stop" : String.valueOf(finishReason);
        return switch (reason) {
            case "stop" -> "done";
            case "length", "max_tokens", "max_output_tokens", "max_iterations", "truncated", "content_filter" -> "truncated";
            case "cancelled", "canceled" -> "cancelled";
            case "interrupted", "timeout" -> "interrupted";
            case "partial" -> "partial";
            default -> "error";
        };
    }

    private boolean shouldSaveNow(String event, int traceSize) {
        // Stages and terminal frames are saved at once; text is saved periodically.
        if (!"delta".equals(event) && !"reasoning".equals(event) && !"agent_output".equals(event)) {
            return true;
        }
        return traceSize % TEXT_SAVE_INTERVAL == 0;
    }

    // ----- helpers -----

    private Map<String, Object> view(AgentRunRecord record) {
        synchronized (lock(record.getRunId())) {
            // A list query may have returned before a waiting writer sealed the row.
            // Refresh columns under the same stripe as the live payload.
            record = runs.findByUserIdAndSessionIdAndRunId(record.getUserId(), record.getSessionId(), record.getRunId())
                    .orElse(record);
            Map<String, Object> payload = read(write(workingCopy(record.getRunId(), record)));
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("runId", record.getRunId());
            view.put("sessionId", record.getSessionId());
            view.put("input", payload.get("input"));
            view.put("operationType", payload.get("operationType"));
            // Only a handler lease in this process proves liveness; historical running
            // rows from a previous process are interrupted in the projection.
            boolean abandoned = "running".equals(payload.get("status")) && !writers.containsKey(record.getRunId());
            view.put("status", abandoned ? "interrupted" : payload.get("status"));
            view.put("scopeSelection", payload.get("scopeSelection"));
            view.put("expectedBindingVersion", payload.get("expectedBindingVersion"));
            if (payload.get("scope") instanceof Map<?, ?> receipt) {
                for (String key : List.of("bindingVersion", "authorizationVersion", "sessionGeneration", "scopeKey")) view.put(key, receipt.get(key));
            }
            view.put("retryOfRunId", payload.get("retryOfRunId"));
            view.put("startedAt", record.getStartedAt() == null ? null : record.getStartedAt().toString());
            view.put("finishedAt", record.getFinishedAt() == null ? null : record.getFinishedAt().toString());
            // No authority, or a run recorded before scopes existed: nothing can be re-confirmed,
            // so private output is not handed back. Only the owner metadata needed to
            // re-authorize (input, selection, versions) stays visible.
            if (scopes == null || payload.get("scope") == null) {
                view.put("scope", null);
                view.put("scopeState", "unavailable");
                return view;
            }
            try { validateScope(record.getUserId(), record.getSessionId(), record.getRunId(), payload); }
            catch (RuntimeException revoked) {
                view.put("scope", null);
                view.put("scopeState", "unavailable");
                return view; // Allowlist only: no private output, trace, refs or errors.
            }
            view.put("scope", payload.get("scope"));
            view.put("scopeState", "available");
            Instant end = record.getFinishedAt() == null ? Instant.now() : record.getFinishedAt();
            Object activeElapsed = payload.get("activeElapsedMs");
            view.put("elapsedMs", activeElapsed instanceof Number number ? number.longValue()
                    : record.getStartedAt() == null ? 0 : Math.max(0, end.toEpochMilli() - record.getStartedAt().toEpochMilli()));
            view.put("elapsedKind", activeElapsed instanceof Number ? "active_attempt_sum" : "legacy_wall_clock_estimate");
            view.put("answer", text(payload.get("answer")));
            view.put("reasoning", text(payload.get("reasoning")));
            view.put("usage", payload.get("usage"));
            view.put("providerRef", payload.get("providerRef"));
            view.put("provider", payload.get("provider"));
            view.put("model", payload.get("model"));
            view.put("options", payload.get("options"));
            view.put("attachmentRefs", payload.get("attachmentRefs"));
            view.put("traceId", payload.get("traceId") != null ? payload.get("traceId") : payload.get("resumeTraceId"));
            view.put("trace", payload.get("trace"));
            view.put("toolCalls", payload.get("toolCalls"));
            view.put("sources", payload.get("sources"));
            view.put("artifacts", payload.get("artifacts"));
            view.put("error", payload.get("error"));
            view.put("diagnostics", payload.getOrDefault("diagnostics", List.of()));
            view.put("finishReason", payload.get("finishReason"));
            view.put("resumable", payload.get("resumable"));
            view.put("attemptId", payload.get("eventAttempt"));
            statistics(view, payload);
            return view;

        }
    }

    private void ownedSession(Long userId, String sessionId) {
        sessions.findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new AgentException(AgentErrorCode.AGENT_AUTHZ_40301, "No permission for this session"));
    }

    private AgentRunRecord owned(Long userId, String sessionId, String runId) {
        ownedSession(userId, sessionId);
        return runs.findByUserIdAndSessionIdAndRunId(userId, sessionId, runId)
                .orElseThrow(() -> new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Run not found"));
    }

    private Map<String, Object> optionsOf(AgentSessionAndChatDtos.SessionChatRequest request) {
        Map<String, Object> options = new LinkedHashMap<>();
        AgentSessionAndChatDtos.Options given = request == null ? null : request.getOptions();
        options.put("maxIterations", given == null || given.getMaxIterations() == null ? 6 : given.getMaxIterations());
        options.put("maxOutputTokens", given == null || given.getMaxOutputTokens() == null ? 1024 : given.getMaxOutputTokens());
        options.put("temperature", given == null || given.getTemperature() == null ? 0.2 : given.getTemperature());
        options.put("collaborationEnabled", given != null && Boolean.TRUE.equals(given.getCollaborationEnabled()));
        options.put("webSearchEnabled", given != null && Boolean.TRUE.equals(given.getWebSearchEnabled()));
        return options;
    }

    private List<Map<String, Object>> attachmentRefs(AgentSessionAndChatDtos.SessionChatRequest request) {
        List<Map<String, Object>> refs = new ArrayList<>();
        if (request == null || request.getResolvedAttachments() == null) return refs;
        for (Map<String, Object> resolved : request.getResolvedAttachments()) {
            Map<String, Object> ref = new LinkedHashMap<>();
            // The body stays in the workspace; the run keeps only the reference.
            ref.put("id", resolved.get("id"));
            ref.put("fileName", resolved.get("fileName"));
            ref.put("sha256", resolved.get("sha256"));
            refs.add(ref);
        }
        return refs;
    }

    /** Drop anything that could carry a credential, at any depth. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> sanitize(Map<String, Object> data) {
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            if (key != null && SECRET_KEYS.stream().anyMatch(secret -> normalizeKey(secret).equals(normalizeKey(key)))) {
                continue;
            }
            clean.put(key, sanitizedValue(entry.getValue()));
        }
        return clean;
    }

    private static String normalizeKey(String key) {
        return java.text.Normalizer.normalize(key, java.text.Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    @SuppressWarnings("unchecked")
    private static Object sanitizedValue(Object value) {
        if (value instanceof Map<?, ?> map) return sanitize((Map<String, Object>) map);
        if (value instanceof List<?> values) {
            List<Object> clean = new ArrayList<>();
            for (Object item : values) clean.add(sanitizedValue(item));
            return clean;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof List<?> existing) {
            return (List<Map<String, Object>>) existing;
        }
        List<Map<String, Object>> fresh = new ArrayList<>();
        payload.put(key, fresh);
        return fresh;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> read(String json) {
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception ex) {
            throw new IllegalStateException("Stored run record is unreadable", ex);
        }
    }

    private String write(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not store run record", ex);
        }
    }
}
