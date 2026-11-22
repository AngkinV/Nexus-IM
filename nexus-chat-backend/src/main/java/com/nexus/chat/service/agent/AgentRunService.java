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

    /**
     * Payloads not yet written. Text arrives faster than it should be stored, so the
     * working copy lives here between saves and is what the next event and the
     * finish step build on.
     */
    private final Map<String, Map<String, Object>> pending = new java.util.concurrent.ConcurrentHashMap<>();

    // ----- lifecycle -----

    public Map<String, Object> begin(Long userId, String sessionId, String runId,
                                     AgentSessionAndChatDtos.SessionChatRequest request) {
        ownedSession(userId, sessionId);
        if (runId == null || !runId.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Invalid run id");
        }
        if (runs.existsByRunId(runId)) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Run id is already in use");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
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
        return view(record);
    }

    /**
     * Record one public event. Unknown events and mismatched run ids are refused
     * rather than written, so a stream can never extend someone else's run.
     */
    public void record(Long userId, String sessionId, String runId, String eventName, Map<String, Object> data) {
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
        // A replayed frame after resume repeats a sequence; the first copy stands.
        Object sequence = clean.get("sequence");
        if (sequence != null && trace.stream().anyMatch(frame -> sequence.equals(frame.get("data") instanceof Map<?, ?> inner
                ? inner.get("sequence") : null) && eventName.equals(frame.get("name")))) {
            return;
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("name", eventName);
        frame.put("data", clean);
        trace.add(frame);
        apply(payload, eventName, clean);
        record.setPayloadJson(write(payload));
        record.setStatus((String) payload.get("status"));
        record.setUpdatedAt(Instant.now());
        pending.put(runId, payload);
        if (shouldSaveNow(eventName, trace.size())) {
            runs.saveAndFlush(record);
        }
    }

    /** Close the run. A stream that ended without a terminal event is interrupted, not done. */
    public Map<String, Object> finish(Long userId, String sessionId, String runId) {
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
        return view(record);
    }

    /** Confirm a resume: only the owner, only a run that stopped before it finished. */
    public Map<String, Object> resume(Long userId, String sessionId, String runId, String traceId) {
        AgentRunRecord record = owned(userId, sessionId, runId);
        Map<String, Object> payload = workingCopy(runId, record);
        // A finished run is not resumed: the agent service hands back what it produced,
        // which is exactly what a client that missed `done` needs. The record stays as it
        // is, and nothing is written twice.
        if (isTerminal(payload.get("status"))) {
            return view(record);
        }
        if (Boolean.TRUE.equals(payload.get("resuming"))) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "This run is already being resumed");
        }
        payload.put("resuming", true);
        payload.put("resumeTraceId", traceId);
        // Each confirmed resume is a new attempt: the trace keeps the old one, the
        // reasoning and answer of the new one start over.
        Object attempt = payload.get("attempt");
        payload.put("attempt", attempt instanceof Number number ? number.intValue() + 1 : 2);
        payload.put("status", "running");
        record.setStatus("running");
        record.setPayloadJson(write(payload));
        record.setUpdatedAt(Instant.now());
        runs.saveAndFlush(record);
        pending.put(runId, payload);
        return view(record);
    }

    private static boolean isTerminal(Object status) {
        return "done".equals(status) || "truncated".equals(status) || "error".equals(status)
                || "cancelled".equals(status);
    }

    /** The payload as this process knows it: unsaved changes included. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> workingCopy(String runId, AgentRunRecord record) {
        Map<String, Object> stored = read(record.getPayloadJson());
        Map<String, Object> live = pending.get(runId);
        if (live == null) return stored;
        // The record's own columns are authoritative for status and timestamps.
        live.put("status", stored.get("status"));
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
            for (Map<String, Object> artifact : list(read(record.getPayloadJson()), "artifacts")) {
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
                .forEach(record -> pending.remove(record.getRunId()));
        runs.deleteByUserIdAndSessionId(userId, sessionId);
    }

    // ----- projection -----

    private void apply(Map<String, Object> payload, String event, Map<String, Object> data) {
        switch (event) {
            case "delta" -> {
                String text = text(data.get("text"));
                if (Boolean.TRUE.equals(data.get("replayed"))) {
                    payload.put("answer", text);
                } else {
                    payload.put("answer", text(payload.get("answer")) + text);
                }
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
            case "usage" -> payload.put("usage", data);
            case "done" -> payload.put("status", statusOf(data.get("finishReason")));
            case "error" -> {
                // A role's own failure is reported by that role; only a run-wide one closes the run.
                boolean roleLocal = data.get("agentId") != null && !"assistant".equals(data.get("agentId"))
                        && !"synth".equals(data.get("agentId"));
                if (!roleLocal || data.get("callId") != null) {
                    payload.put("status", "error");
                }
                payload.put("error", data);
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
            case "length", "max_tokens", "max_output_tokens", "max_iterations", "truncated" -> "truncated";
            case "cancelled", "canceled" -> "cancelled";
            case "interrupted" -> "interrupted";
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
        Map<String, Object> payload = read(record.getPayloadJson());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", record.getRunId());
        view.put("sessionId", record.getSessionId());
        view.put("input", payload.get("input"));
        view.put("operationType", payload.get("operationType"));
        // A row still marked running with no finish time belongs to a process that stopped
        // mid-turn: it is interrupted, not live.
        boolean abandoned = "running".equals(record.getStatus()) && record.getFinishedAt() == null;
        view.put("status", abandoned ? "interrupted" : record.getStatus());
        view.put("startedAt", record.getStartedAt() == null ? null : record.getStartedAt().toString());
        view.put("finishedAt", record.getFinishedAt() == null ? null : record.getFinishedAt().toString());
        Instant end = record.getFinishedAt() == null ? Instant.now() : record.getFinishedAt();
        view.put("elapsedMs", record.getStartedAt() == null ? 0
                : Math.max(0, end.toEpochMilli() - record.getStartedAt().toEpochMilli()));
        view.put("answer", text(payload.get("answer")));
        view.put("reasoning", text(payload.get("reasoning")));
        view.put("usage", payload.get("usage"));
        view.put("providerRef", payload.get("providerRef"));
        view.put("provider", payload.get("provider"));
        view.put("model", payload.get("model"));
        view.put("options", payload.get("options"));
        view.put("attachmentRefs", payload.get("attachmentRefs"));
        view.put("retryOfRunId", payload.get("retryOfRunId"));
        view.put("traceId", payload.get("traceId") != null ? payload.get("traceId") : payload.get("resumeTraceId"));
        view.put("trace", payload.get("trace"));
        view.put("toolCalls", payload.get("toolCalls"));
        view.put("sources", payload.get("sources"));
        view.put("artifacts", payload.get("artifacts"));
        view.put("error", payload.get("error"));
        return view;
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
            if (key != null && SECRET_KEYS.contains(key.toLowerCase().replace("-", "").replace("_", ""))) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                clean.put(key, sanitize((Map<String, Object>) map));
            } else if (value instanceof List<?> list) {
                List<Object> items = new ArrayList<>();
                for (Object item : list) {
                    items.add(item instanceof Map<?, ?> nested ? sanitize((Map<String, Object>) nested) : item);
                }
                clean.put(key, items);
            } else {
                clean.put(key, value);
            }
        }
        return clean;
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
