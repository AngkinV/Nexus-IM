package com.nexus.chat.controller;

import com.nexus.chat.dto.*;
import com.nexus.chat.model.CompanionModelBinding;
import com.nexus.chat.security.CurrentUser;
import com.nexus.chat.service.CompanionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/companion")
@RequiredArgsConstructor
public class CompanionController {

    private final CompanionService companionService;
    private final com.nexus.chat.service.CompanionConversationGuard conversationGuard;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @PostMapping("/roles/init")
    public ResponseEntity<List<CompanionRoleDTO>> initRoles(@RequestParam(name = "userId", required = false) Long claimedUserId) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            return ResponseEntity.ok(companionService.initDefaultRoles(userId));
        } catch (Exception e) {
            log.error("Init roles failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/roles")
    public ResponseEntity<List<CompanionRoleDTO>> getRoles(@RequestParam(name = "userId", required = false) Long claimedUserId) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            return ResponseEntity.ok(companionService.getRoles(userId));
        } catch (Exception e) {
            log.error("Get roles failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/roles/{roleId}")
    public ResponseEntity<CompanionRoleDTO> updateRole(
            @PathVariable Long roleId,
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestBody CompanionRoleDTO update) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            return ResponseEntity.ok(companionService.updateRole(userId, roleId, update));
        } catch (Exception e) {
            log.error("Update role failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/conversations/{roleId}")
    public ResponseEntity<List<CompanionMessageDTO>> getConversation(
            @PathVariable Long roleId,
            @RequestParam(name = "userId", required = false) Long claimedUserId) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            return ResponseEntity.ok(companionService.getConversation(userId, roleId));
        } catch (Exception e) {
            log.error("Get conversation failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/messages")
    public ResponseEntity<CompanionChatResponse> sendMessage(
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestBody Map<String, Object> request) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            Long roleId = Long.valueOf(request.get("roleId").toString());
            String content = request.get("content") == null ? "" : request.get("content").toString();
            String sceneKey = request.get("sceneKey") == null ? null : request.get("sceneKey").toString();
            String clientMsgId = request.get("clientMsgId") == null ? null : request.get("clientMsgId").toString();
            try (var ignored = conversationGuard.acquire(userId, roleId)) {
                return ResponseEntity.ok(companionService.sendMessage(userId, roleId, content, sceneKey, clientMsgId));
            }
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Send companion message failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Streaming chat. Mirrors /api/agent's stream endpoint: written straight onto
     * the response stream, with one `data:` event per delta and a final `done`
     * carrying the persisted messages so the client can settle on server state.
     */
    @PostMapping(value = "/messages/stream", produces = "text/event-stream")
    public void sendMessageStream(
            @RequestBody Map<String, Object> request,
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        Long userId = CurrentUser.require(claimedUserId);
        Object roleIdRaw = request.get("roleId");
        Object contentRaw = request.get("content");
        if (roleIdRaw == null || contentRaw == null) {
            response.setStatus(400);
            return;
        }
        Long roleId = Long.valueOf(roleIdRaw.toString());
        String content = contentRaw.toString();
        Object sceneRaw = request.get("sceneKey");
        String sceneKey = sceneRaw == null ? null : sceneRaw.toString();
        Object clientRaw = request.get("clientMsgId");
        String clientMsgId = clientRaw == null ? null : clientRaw.toString();

        response.setStatus(200);
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        java.io.PrintWriter writer = response.getWriter();
        java.util.concurrent.atomic.AtomicBoolean open = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (var ignored = conversationGuard.acquire(userId, roleId)) {
            CompanionChatResponse result = companionService.sendMessageStreaming(
                    userId, roleId, content, sceneKey, clientMsgId,
                    piece -> {
                        // Throwing here is the signal: the service stops the model
                        // and keeps what was already written. PrintWriter swallows
                        // IO errors, so a closed socket only shows up in checkError.
                        if (!open.get()) throw new IllegalStateException("client gone");
                        writer.write("event: delta\ndata: " + escapeJson(piece) + "\n\n");
                        if (writer.checkError()) {
                            open.set(false);
                            throw new IllegalStateException("client gone");
                        }
                    },
                    (event, payload) -> {
                        if (!open.get()) return;
                        try {
                            writer.write("event: " + event + "\ndata: " + objectMapper.writeValueAsString(payload) + "\n\n");
                            if (writer.checkError()) open.set(false);
                        } catch (Exception e) {
                            // A side-channel event that cannot be serialized must not end the reply.
                            log.warn("Companion stream event '{}' dropped: {}", event, e.getMessage());
                        }
                    });
            if (open.get()) {
                writer.write("event: done\ndata: " + objectMapper.writeValueAsString(result) + "\n\n");
                writer.flush();
            }
        } catch (Exception e) {
            log.error("Companion stream failed", e);
            if (open.get()) {
                writer.write("event: error\ndata: " + escapeJson(e.getMessage() == null ? "failed" : e.getMessage()) + "\n\n");
                writer.flush();
            }
        }
    }

    private static Double asDouble(Object value) {
        if (value == null) return null;
        try {
            return Double.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer asInt(Object value) {
        if (value == null) return null;
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Minimal JSON string escaping for a single SSE payload. */
    private static String escapeJson(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return "\"" + out + "\"";
    }

    @GetMapping("/memories")
    public ResponseEntity<List<CompanionMemoryDTO>> getMemories(
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestParam Long roleId) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            return ResponseEntity.ok(companionService.getMemories(userId, roleId));
        } catch (Exception e) {
            log.error("Get memories failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/memories")
    public ResponseEntity<CompanionMemoryDTO> createMemory(
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestBody Map<String, Object> request) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            Long roleId = Long.valueOf(request.get("roleId").toString());
            String type = request.get("type") != null ? request.get("type").toString() : "mid";
            String content = request.get("content") != null ? request.get("content").toString() : "";
            return ResponseEntity.ok(companionService.createMemory(userId, roleId, type, content));
        } catch (Exception e) {
            log.error("Create memory failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/memories/{id}/confirm")
    public ResponseEntity<CompanionMemoryDTO> confirmMemory(
            @PathVariable Long id,
            @RequestParam(name = "userId", required = false) Long claimedUserId) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            return ResponseEntity.ok(companionService.confirmMemory(userId, id));
        } catch (Exception e) {
            log.error("Confirm memory failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/memories/{id}")
    public ResponseEntity<Void> deleteMemory(
            @PathVariable Long id,
            @RequestParam(name = "userId", required = false) Long claimedUserId) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            companionService.deleteMemory(userId, id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Delete memory failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/memories")
    public ResponseEntity<Void> clearMemories(
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestParam Long roleId) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            companionService.clearMemories(userId, roleId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Clear memories failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Her inner monologue, generated on demand. Called only when the user opens
     * the whisper layer — it is a model call, so it must not run on page load.
     */
    @PostMapping("/inner-voice/{roleId}")
    public ResponseEntity<CompanionInnerVoiceDTO> innerVoice(
            @PathVariable Long roleId,
            @RequestParam(name = "userId", required = false) Long claimedUserId) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            return ResponseEntity.ok(companionService.generateInnerVoice(userId, roleId));
        } catch (Exception e) {
            log.error("Inner voice failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * What the tavern is wired to, and which piece is missing when it is not.
     * No model call — safe to poll from the settings panel.
     */
    @GetMapping("/model-status/{roleId}")
    public ResponseEntity<Map<String, Object>> modelStatus(
            @PathVariable Long roleId,
            @RequestParam(name = "userId", required = false) Long claimedUserId) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            return ResponseEntity.ok(companionService.describeModel(userId, roleId));
        } catch (Exception e) {
            log.error("Describe model failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Probe the model channel with one tiny request, so "cannot reach the model"
     * stops being a guess. Returns ok / the provider's own error text.
     */
    @PostMapping("/model-test/{roleId}")
    public ResponseEntity<Map<String, Object>> modelTest(
            @PathVariable Long roleId,
            @RequestParam(name = "userId", required = false) Long claimedUserId) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            return ResponseEntity.ok(companionService.testModelChannel(userId, roleId));
        } catch (Exception e) {
            log.error("Model test failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/growth/{roleId}")
    public ResponseEntity<CompanionGrowthDTO> getGrowth(
            @PathVariable Long roleId,
            @RequestParam(name = "userId", required = false) Long claimedUserId) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            return ResponseEntity.ok(companionService.getGrowth(userId, roleId));
        } catch (Exception e) {
            log.error("Get growth failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/status/{roleId}")
    public ResponseEntity<CompanionStatusDTO> getStatus(
            @PathVariable Long roleId,
            @RequestParam(name = "userId", required = false) Long claimedUserId) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            return ResponseEntity.ok(companionService.getStatus(userId, roleId));
        } catch (Exception e) {
            log.error("Get status failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/status/{roleId}")
    public ResponseEntity<CompanionStatusDTO> updateStatus(
            @PathVariable Long roleId,
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestBody Map<String, Object> request) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            String statusType = request.get("statusType") != null ? request.get("statusType").toString() : null;
            String summary = request.get("summary") != null ? request.get("summary").toString() : null;
            return ResponseEntity.ok(companionService.updateStatus(userId, roleId, statusType, summary));
        } catch (Exception e) {
            log.error("Update status failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/model-credential")
    public ResponseEntity<ModelCredentialStatusDTO> saveCredential(
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestBody Map<String, Object> request) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            String provider = request.get("provider") != null ? request.get("provider").toString() : "openai-compatible";
            String apiKey = request.get("apiKey") != null ? request.get("apiKey").toString() : null;
            if (apiKey == null || apiKey.isBlank()) {
                return ResponseEntity.badRequest().build();
            }
            return ResponseEntity.ok(companionService.saveCredential(userId, provider, apiKey));
        } catch (Exception e) {
            log.error("Save credential failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/model-credential/status")
    public ResponseEntity<ModelCredentialStatusDTO> getCredentialStatus(
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestParam(defaultValue = "openai-compatible") String provider) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            return ResponseEntity.ok(companionService.getCredentialStatus(userId, provider));
        } catch (Exception e) {
            log.error("Get credential status failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/model-binding/{roleId}")
    public ResponseEntity<CompanionModelBinding> bindModel(
            @PathVariable Long roleId,
            @RequestParam(name = "userId", required = false) Long claimedUserId,
            @RequestBody Map<String, Object> request) {
        Long userId = CurrentUser.require(claimedUserId);
        try {
            String provider = request.get("provider") != null ? request.get("provider").toString() : "openai-compatible";
            String modelName = request.get("modelName") != null ? request.get("modelName").toString() : null;
            String endpoint = request.get("endpoint") != null ? request.get("endpoint").toString() : null;
            return ResponseEntity.ok(companionService.bindModel(
                    userId, roleId, provider, modelName, endpoint,
                    asDouble(request.get("temperature")),
                    asInt(request.get("personaLock")),
                    asInt(request.get("contextHorizon"))));
        } catch (Exception e) {
            log.error("Bind model failed", e);
            return ResponseEntity.badRequest().build();
        }
    }
}
