package com.nexus.chat.controller.agent;

import com.nexus.chat.dto.agent.AgentCommonResponse;
import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.security.JwtTokenProvider;
import com.nexus.chat.service.agent.AgentSessionService;
import com.nexus.chat.service.agent.AgentWorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The files and tasks of one agent session.
 *
 * Every route checks ownership of the session first, so an ID from another
 * session or another user never resolves. Uploads are text or documents only;
 * what the model reads is the text Python extracted, never a path.
 */
@RestController
@RequestMapping("/api/agent/sessions/{sessionId}")
@RequiredArgsConstructor
public class AgentWorkspaceController {

    private final AgentWorkspaceService agentWorkspaceService;
    private final AgentSessionService agentSessionService;
    private final JwtTokenProvider jwtTokenProvider;

    @GetMapping("/attachments")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> listAttachments(
            @PathVariable String sessionId, HttpServletRequest httpRequest) {
        Long userId = requireUserId(httpRequest);
        agentSessionService.mustOwn(userId, sessionId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("attachments", agentWorkspaceService.listAttachments(userId, sessionId));
        data.put("tasks", agentWorkspaceService.listTasks(userId, sessionId));
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), data));
    }

    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> upload(
            @PathVariable String sessionId,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest httpRequest) {
        Long userId = requireUserId(httpRequest);
        agentSessionService.mustOwn(userId, sessionId);
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest),
                agentWorkspaceService.upload(userId, sessionId, file)));
    }

    @DeleteMapping("/attachments/{attachmentId}")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> deleteAttachment(
            @PathVariable String sessionId, @PathVariable String attachmentId, HttpServletRequest httpRequest) {
        Long userId = requireUserId(httpRequest);
        agentSessionService.mustOwn(userId, sessionId);
        agentWorkspaceService.deleteAttachment(userId, sessionId, attachmentId);
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), Map.of("deleted", true)));
    }

    @GetMapping("/tasks")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> listTasks(
            @PathVariable String sessionId, HttpServletRequest httpRequest) {
        Long userId = requireUserId(httpRequest);
        agentSessionService.mustOwn(userId, sessionId);
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest),
                Map.of("tasks", agentWorkspaceService.listTasks(userId, sessionId))));
    }

    @PatchMapping("/tasks/{taskId}")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> updateTask(
            @PathVariable String sessionId, @PathVariable String taskId,
            @RequestBody Map<String, Object> body, HttpServletRequest httpRequest) {
        Long userId = requireUserId(httpRequest);
        agentSessionService.mustOwn(userId, sessionId);
        Object status = body == null ? null : body.get("status");
        if (!(status instanceof String text)) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "A task status is required");
        }
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest),
                agentWorkspaceService.updateTask(userId, sessionId, taskId, text)));
    }

    @PostMapping("/artifacts/{artifactId}/patch-preview")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> previewPatch(
            @PathVariable String sessionId, @PathVariable String artifactId,
            @RequestBody Map<String, Object> body, HttpServletRequest httpRequest) {
        Long userId = requireUserId(httpRequest);
        agentSessionService.mustOwn(userId, sessionId);
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest),
                agentWorkspaceService.previewPatch(userId, sessionId, artifactId, requireText(body, "baseAttachmentId"))));
    }

    @PostMapping("/artifacts/{artifactId}/apply")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> applyPatch(
            @PathVariable String sessionId, @PathVariable String artifactId,
            @RequestBody Map<String, Object> body, HttpServletRequest httpRequest) {
        Long userId = requireUserId(httpRequest);
        agentSessionService.mustOwn(userId, sessionId);
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest),
                agentWorkspaceService.applyPatch(userId, sessionId, artifactId,
                        requireText(body, "baseAttachmentId"), requireText(body, "baseHash"),
                        requireText(body, "patchHash"), Boolean.TRUE.equals(body.get("confirmed")))));
    }

    @PostMapping("/artifacts/{artifactId}/tasks")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> createTasks(
            @PathVariable String sessionId, @PathVariable String artifactId, HttpServletRequest httpRequest) {
        Long userId = requireUserId(httpRequest);
        agentSessionService.mustOwn(userId, sessionId);
        List<Map<String, Object>> created = agentWorkspaceService.createTasks(userId, sessionId, artifactId);
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), Map.of("tasks", created)));
    }

    @PostMapping("/artifacts/{artifactId}/save")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> saveArtifact(
            @PathVariable String sessionId, @PathVariable String artifactId, HttpServletRequest httpRequest) {
        Long userId = requireUserId(httpRequest);
        agentSessionService.mustOwn(userId, sessionId);
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest),
                agentWorkspaceService.saveArtifact(userId, sessionId, artifactId)));
    }

    private static String requireText(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "A value is required for " + key);
        }
        return text;
    }

    private Long requireUserId(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new AgentException(AgentErrorCode.AGENT_AUTH_40101, "Missing bearer token");
        }
        return jwtTokenProvider.getUserIdFromToken(header.substring(7));
    }

    private String ensureRequestId(HttpServletRequest request) {
        Object existing = request.getAttribute("requestId");
        if (existing instanceof String text && !text.isBlank()) return text;
        String created = "req_" + UUID.randomUUID().toString().replace("-", "");
        request.setAttribute("requestId", created);
        return created;
    }
}
