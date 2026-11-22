package com.nexus.chat.controller.agent;

import com.nexus.chat.dto.agent.AgentCommonResponse;
import com.nexus.chat.dto.agent.AgentEnums;
import com.nexus.chat.dto.agent.AgentModeBDtos;
import com.nexus.chat.dto.agent.AgentSessionAndChatDtos;
import com.nexus.chat.dto.agent.AgentSessionDtos;
import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.model.Message;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.repository.MessageRepository;
import com.nexus.chat.security.JwtTokenProvider;
import com.nexus.chat.service.agent.AgentGatewayService;
import com.nexus.chat.service.agent.AgentSessionService;
import com.nexus.chat.service.agent.memory.AgentMemoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.nexus.chat.service.agent.AgentRunService;
import com.nexus.chat.service.agent.AgentWorkspaceService;
import com.nexus.chat.service.agent.SseRecordingOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentGatewayService agentGatewayService;
    private final JwtTokenProvider jwtTokenProvider;
    private final ChatMemberRepository chatMemberRepository;
    private final MessageRepository messageRepository;
    private final AgentMemoryService agentMemoryService;
    private final AgentSessionService agentSessionService;
    private final AgentRunService agentRunService;
    private final AgentWorkspaceService agentWorkspaceService;

    @PostMapping("/sessions")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> createSession(
            @Valid @RequestBody AgentSessionAndChatDtos.CreateSessionRequest request,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        String requestId = ensureRequestId(httpRequest);
        AgentSessionDtos.SessionView created = agentSessionService.create(userId, request.getTitle());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", created.getSessionId());
        data.put("entryMode", request.getEntryMode().name());
        data.put("title", created.getTitle());
        data.put("boundChatId", request.getBoundChatId());
        data.put("createdAt", created.getCreatedAt());
        data.put("updatedAt", created.getUpdatedAt());
        data.put("ownerUserId", userId);
        return ResponseEntity.ok(AgentCommonResponse.ok(requestId, data));
    }

    @GetMapping("/sessions")
    public ResponseEntity<AgentCommonResponse<List<AgentSessionDtos.SessionView>>> listSessions(HttpServletRequest httpRequest) {
        Long userId = requireUserId(httpRequest);
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), agentSessionService.listForUser(userId)));
    }

    @PatchMapping("/sessions/{sessionId}")
    public ResponseEntity<AgentCommonResponse<AgentSessionDtos.SessionView>> renameSession(
            @PathVariable String sessionId,
            @Valid @RequestBody AgentSessionDtos.RenameRequest request,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest),
                agentSessionService.rename(userId, sessionId, request.getTitle())));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> deleteSession(
            @PathVariable String sessionId,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        agentSessionService.mustOwn(userId, sessionId);
        agentGatewayService.purgeRuns(userId, ensureRequestId(httpRequest), sessionId);
        agentRunService.deleteSession(userId, sessionId);
        agentWorkspaceService.deleteSession(userId, sessionId);
        agentSessionService.delete(userId, sessionId);
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), Map.of("deleted", true)));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> sessionMessages(
            @PathVariable String sessionId,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest),
                Map.of("sessionId", sessionId, "messages", agentSessionService.messagesOf(userId, sessionId))));
    }

    @PostMapping("/sessions/{sessionId}/chat")
    public ResponseEntity<AgentCommonResponse<AgentSessionAndChatDtos.ChatAnswerData>> chat(
            @PathVariable String sessionId,
            @Valid @RequestBody AgentSessionAndChatDtos.SessionChatRequest request,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        String username = getUsername(httpRequest);
        String requestId = ensureRequestId(httpRequest);
        agentSessionService.mustOwn(userId, sessionId);
        requireOwnedRetry(userId, sessionId, request);
        String traceId = "tr_" + UUID.randomUUID().toString().replace("-", "");
        checkChatMembershipIfPresent(userId, request.getChatContext() != null ? request.getChatContext().getChatId() : null);
        agentSessionService.touchAndAutoTitle(userId, sessionId, request.getInput());

        Map<String, Object> raw = agentGatewayService.invokeNonStream(userId, username, traceId, sessionId, request);
        Map<String, Object> result = (Map<String, Object>) raw.getOrDefault("result", Map.of());

        AgentSessionAndChatDtos.ChatAnswerData data = new AgentSessionAndChatDtos.ChatAnswerData();
        data.setAnswer((String) result.getOrDefault("answer", ""));
        data.setOperationType(request.getOperationType());
        data.setFinishReason((String) result.getOrDefault("finishReason", "stop"));
        data.setRaw(raw);

        Object toolCallsObj = result.get("toolCalls");
        if (toolCallsObj instanceof List<?> list) {
            List<AgentSessionAndChatDtos.ToolCallSummary> toolCalls = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    AgentSessionAndChatDtos.ToolCallSummary t = new AgentSessionAndChatDtos.ToolCallSummary();
                    t.setToolName((String) m.get("toolName"));
                    t.setStatus((String) m.get("status"));
                    Object latency = m.get("latencyMs");
                    t.setLatencyMs(latency instanceof Number n ? n.intValue() : null);
                    toolCalls.add(t);
                }
            }
            data.setToolCalls(toolCalls);
        }

        if (result.get("usage") instanceof Map<?, ?> usageMap) {
            AgentSessionAndChatDtos.TokenUsage usage = new AgentSessionAndChatDtos.TokenUsage();
            usage.setInputTokens(toInt(usageMap.get("inputTokens")));
            usage.setOutputTokens(toInt(usageMap.get("outputTokens")));
            usage.setTotalTokens(toInt(usageMap.get("totalTokens")));
            data.setUsage(usage);
        }

        return ResponseEntity.ok(AgentCommonResponse.ok(requestId, data));
    }

    @PostMapping(value = "/sessions/{sessionId}/chat/stream", produces = "text/event-stream")
    public void chatStream(
            @PathVariable String sessionId,
            @Valid @RequestBody AgentSessionAndChatDtos.SessionChatRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String clientTraceId,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) throws IOException {
        Long userId = requireUserId(httpRequest);
        String username = getUsername(httpRequest);
        String traceId = (clientTraceId == null || clientTraceId.isBlank())
                ? "tr_" + UUID.randomUUID().toString().replace("-", "")
                : clientTraceId;
        checkChatMembershipIfPresent(userId, request.getChatContext() != null ? request.getChatContext().getChatId() : null);
        agentSessionService.mustOwn(userId, sessionId);
        requireOwnedRetry(userId, sessionId, request);
        if (request.getRunId() == null || request.getRunId().isBlank()) {
            request.setRunId("run_" + UUID.randomUUID().toString().replace("-", ""));
        }
        if (request.getAttachmentIds() != null && !request.getAttachmentIds().isEmpty()) {
            request.setResolvedAttachments(
                    agentWorkspaceService.resolveAttachments(userId, sessionId, request.getAttachmentIds()));
        }
        agentRunService.begin(userId, sessionId, request.getRunId(), request);
        // Auto-title from the first user message + bump updated_at; the Python orchestrator
        // is responsible for actually appending this turn to short-term memory.
        agentSessionService.touchAndAutoTitle(userId, sessionId, request.getInput());

        // Drive the stream synchronously on the request thread so we never let Spring's async
        // dispatch close the response prematurely (which previously produced
        // net::ERR_INCOMPLETE_CHUNKED_ENCODING in the browser).
        httpResponse.setStatus(HttpServletResponse.SC_OK);
        httpResponse.setContentType("text/event-stream;charset=UTF-8");
        httpResponse.setHeader("Cache-Control", "no-cache, no-transform");
        httpResponse.setHeader("Connection", "keep-alive");
        httpResponse.setHeader("X-Accel-Buffering", "no"); // disable buffering for nginx-like proxies
        httpResponse.setHeader("X-Trace-Id", traceId);

        OutputStream out = httpResponse.getOutputStream();
        try {
            agentGatewayService.streamPythonRaw(userId, username, traceId, sessionId, request,
                    new SseRecordingOutputStream(out, (name, data) -> { },
                            agentRunService, userId, sessionId, request.getRunId()));
            out.flush();
        } finally {
            // Even a disconnect ends the run: what was recorded is what happened.
            agentRunService.finish(userId, sessionId, request.getRunId());
        }
    }

    @GetMapping("/sessions/{sessionId}/runs")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> listRuns(
            @PathVariable String sessionId, HttpServletRequest httpRequest) {
        Long userId = requireUserId(httpRequest);
        agentSessionService.mustOwn(userId, sessionId);
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest),
                Map.of("runs", agentRunService.list(userId, sessionId))));
    }

    @GetMapping("/sessions/{sessionId}/runs/pending")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> pendingRuns(
            @PathVariable String sessionId, HttpServletRequest httpRequest) {
        Long userId = requireUserId(httpRequest);
        agentSessionService.mustOwn(userId, sessionId);
        String traceId = ensureRequestId(httpRequest);
        return ResponseEntity.ok(AgentCommonResponse.ok(traceId,
                agentGatewayService.pendingRuns(userId, traceId, sessionId)));
    }

    @PostMapping(value = "/sessions/{sessionId}/runs/{runId}/resume/stream", produces = "text/event-stream")
    public void resumeStream(@PathVariable String sessionId, @PathVariable String runId,
            @Valid @RequestBody AgentSessionAndChatDtos.ResumeRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException {
        Long userId = requireUserId(httpRequest);
        agentSessionService.mustOwn(userId, sessionId);
        if (!runId.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Invalid run id");
        }
        String traceId = "tr_" + UUID.randomUUID().toString().replace("-", "");
        httpResponse.setContentType("text/event-stream;charset=UTF-8");
        httpResponse.setHeader("Cache-Control", "no-cache, no-transform");
        httpResponse.setHeader("X-Accel-Buffering", "no");
        httpResponse.setHeader("X-Trace-Id", traceId);
        boolean owned = agentRunService.list(userId, sessionId).stream()
                .anyMatch(run -> runId.equals(run.get("runId")));
        OutputStream out = httpResponse.getOutputStream();
        if (!owned) {
            // A checkpoint from before run records existed: it has no record to extend.
            agentGatewayService.resumePythonRaw(userId, getUsername(httpRequest), traceId, sessionId,
                    runId, request.getProviderId(), out);
            out.flush();
            return;
        }
        boolean finished = "done".equals(String.valueOf(agentRunService.resume(userId, sessionId, runId, traceId).get("status")));
        try {
            agentGatewayService.resumePythonRaw(userId, getUsername(httpRequest), traceId, sessionId,
                    runId, request.getProviderId(),
                    new SseRecordingOutputStream(out, (name, data) -> { },
                            agentRunService, userId, sessionId, runId));
            out.flush();
        } finally {
            // A finished run that was only handed back must keep its recorded outcome.
            if (!finished) agentRunService.finish(userId, sessionId, runId);
        }
    }

    @DeleteMapping("/sessions/{sessionId}/memory")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> clearSessionMemory(
            @PathVariable String sessionId,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        agentSessionService.mustOwn(userId, sessionId);
        agentGatewayService.purgeRuns(userId, ensureRequestId(httpRequest), sessionId);
        agentRunService.deleteSession(userId, sessionId);
        agentWorkspaceService.deleteSession(userId, sessionId);
        agentMemoryService.clearShortTerm(userId, sessionId);
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), Map.of("cleared", true)));
    }

    @DeleteMapping("/memory/{memoryId}")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> disableMemory(
            @PathVariable Long memoryId,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        agentMemoryService.disableLongTerm(memoryId, userId, "user_delete");
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), Map.of("disabled", true)));
    }

    @PostMapping("/memory/reset")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> resetMemory(HttpServletRequest httpRequest) {
        Long userId = requireUserId(httpRequest);
        agentMemoryService.getLongTermTopK(userId)
                .forEach(item -> agentMemoryService.disableLongTerm(item.getId(), userId, "user_reset"));
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), Map.of("reset", true)));
    }

    @PostMapping("/chats/{chatId}/summarize")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> summarize(
            @PathVariable Long chatId,
            @Valid @RequestBody AgentModeBDtos.SummarizeRequest request,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        String username = getUsername(httpRequest);
        checkChatMembershipIfPresent(userId, chatId);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("summaryRangeType", request.getSummaryRangeType().name());
        extra.put("rangeValue", request.getRangeValue() == null ? 80 : request.getRangeValue());
        extra.put("outputStyle", request.getOutputStyle());

        Map<String, Object> raw = agentGatewayService.invokeOneShot(
                userId, username, AgentEnums.OperationType.CHAT_SUMMARY,
                "总结本会话最近 " + extra.get("rangeValue") + " 条消息", chatId, extra);
        Map<String, Object> result = (Map<String, Object>) raw.getOrDefault("result", Map.of());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", result.getOrDefault("answer", ""));
        data.put("participants", chatMemberRepository.findByChatId(chatId).stream().map(cm -> "u_" + cm.getUserId()).toList());
        data.put("messageCount", messageRepository.countByChatId(chatId));
        data.put("toolCalls", result.get("toolCalls"));
        data.put("usage", result.get("usage"));
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), data));
    }

    @PostMapping("/chats/{chatId}/todo-extract")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> todoExtract(
            @PathVariable Long chatId,
            @Valid @RequestBody AgentModeBDtos.TodoExtractRequest request,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        String username = getUsername(httpRequest);
        checkChatMembershipIfPresent(userId, chatId);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("summaryRangeType", request.getSummaryRangeType().name());
        extra.put("rangeValue", request.getRangeValue() == null ? 24 : request.getRangeValue());

        Map<String, Object> raw = agentGatewayService.invokeOneShot(
                userId, username, AgentEnums.OperationType.TODO_EXTRACT,
                "从最近的会话消息中提炼待办事项", chatId, extra);
        Map<String, Object> result = (Map<String, Object>) raw.getOrDefault("result", Map.of());

        Map<String, Object> data = new LinkedHashMap<>();
        Object todos = result.get("todos");
        data.put("todos", todos instanceof List<?> ? todos : List.of());
        data.put("rawAnswer", result.getOrDefault("answer", ""));
        data.put("toolCalls", result.get("toolCalls"));
        data.put("usage", result.get("usage"));
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), data));
    }

    @PostMapping("/chats/{chatId}/reply-suggest")
    public ResponseEntity<AgentCommonResponse<AgentModeBDtos.ReplySuggestData>> replySuggest(
            @PathVariable Long chatId,
            @Valid @RequestBody AgentModeBDtos.ReplySuggestRequest request,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        String username = getUsername(httpRequest);
        checkChatMembershipIfPresent(userId, chatId);
        Message target = messageRepository.findById(request.getTargetMessageId())
                .filter(m -> Objects.equals(m.getChatId(), chatId))
                .orElseThrow(() -> new AgentException(AgentErrorCode.AGENT_PARAM_40001, "targetMessageId not in chat",
                        Map.of("targetMessageId", request.getTargetMessageId(), "chatId", chatId)));

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("targetMessageId", request.getTargetMessageId());
        extra.put("targetMessageContent", target.getContent());
        extra.put("tone", request.getTone());
        extra.put("length", request.getLength());

        Map<String, Object> raw = agentGatewayService.invokeOneShot(
                userId, username, AgentEnums.OperationType.REPLY_SUGGEST,
                "为目标消息生成回复建议", chatId, extra);
        Map<String, Object> result = (Map<String, Object>) raw.getOrDefault("result", Map.of());

        AgentModeBDtos.ReplySuggestData data = new AgentModeBDtos.ReplySuggestData();
        data.setDraft((String) result.getOrDefault("draft", result.getOrDefault("answer", "")));
        Object alternatives = result.get("alternatives");
        if (alternatives instanceof List<?> list) {
            data.setAlternatives(list.stream().filter(o -> o instanceof String).map(String.class::cast).toList());
        } else {
            data.setAlternatives(List.of());
        }
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), data));
    }

    @PostMapping("/chats/{chatId}/reply-publish")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> replyPublish(
            @PathVariable Long chatId,
            @Valid @RequestBody AgentModeBDtos.ReplyPublishRequest request,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        checkChatMembershipIfPresent(userId, chatId);

        Message message = new Message();
        message.setChatId(chatId);
        message.setSenderId(userId);
        message.setMessageType(Message.MessageType.text);
        message.setContent(request.getDraft());
        Message saved = messageRepository.save(message);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", saved.getId());
        data.put("chatId", chatId);
        data.put("publishedAt", OffsetDateTime.now().toString());
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), data));
    }

    private Integer toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    private void checkChatMembershipIfPresent(Long userId, Long chatId) {
        if (chatId == null) {
            return;
        }
        if (!chatMemberRepository.existsByChatIdAndUserId(chatId, userId)) {
            throw new AgentException(AgentErrorCode.AGENT_AUTHZ_40301, "No permission for this chat", Map.of("chatId", chatId));
        }
    }

    /** A retry may only name a run of this session; otherwise it would be a new question. */
    private void requireOwnedRetry(Long userId, String sessionId, AgentSessionAndChatDtos.SessionChatRequest request) {
        if (request.getRetryOfRunId() == null) return;
        boolean owned = agentRunService.list(userId, sessionId).stream()
                .anyMatch(run -> request.getRetryOfRunId().equals(run.get("runId")));
        if (!owned) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "The run being retried does not belong to this session");
        }
    }

    private Long requireUserId(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new AgentException(AgentErrorCode.AGENT_AUTH_40101, "JWT missing or invalid");
        }
        return jwtTokenProvider.getUserIdFromToken(auth.substring(7));
    }

    private String getUsername(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        return jwtTokenProvider.getUsernameFromToken(auth.substring(7));
    }

    private String ensureRequestId(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }
}
