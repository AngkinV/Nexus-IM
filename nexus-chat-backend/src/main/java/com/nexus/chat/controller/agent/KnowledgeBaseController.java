package com.nexus.chat.controller.agent;

import com.nexus.chat.dto.agent.AgentCommonResponse;
import com.nexus.chat.dto.agent.KnowledgeBaseDtos;
import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.security.JwtTokenProvider;
import com.nexus.chat.service.agent.KnowledgeBaseService;
import com.nexus.chat.service.agent.KnowledgeIngestionScheduler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Browser-facing CRUD for Module B knowledge bases. Mirrors
 * {@code RAG扩展实施方案.md §4.1} — 9 endpoints across two resource types.
 *
 * <p>Authentication mirrors {@link AgentController}: JWT extracted from the
 * {@code Authorization: Bearer <token>} header; ownership re-checked at the
 * service layer through {@code findByKbIdAndUserId}, so a forged kbId from
 * another user surfaces as a 404 rather than leaking the row.
 */
@RestController
@RequestMapping("/api/agent/knowledge")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;
    private final KnowledgeIngestionScheduler ingestionScheduler;
    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping
    public ResponseEntity<AgentCommonResponse<KnowledgeBaseDtos.KbView>> createKb(
            @Valid @RequestBody KnowledgeBaseDtos.CreateKbRequest request,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        return ResponseEntity.ok(AgentCommonResponse.ok(
                ensureRequestId(httpRequest), service.create(userId, request)));
    }

    @GetMapping
    public ResponseEntity<AgentCommonResponse<KnowledgeBaseDtos.KbListView>> listKb(
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        List<KnowledgeBaseDtos.KbView> items = service.listForUser(userId);
        KnowledgeBaseDtos.KbListView view = new KnowledgeBaseDtos.KbListView();
        view.setItems(items);
        view.setTotal(items.size());
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), view));
    }

    @GetMapping("/{kbId}")
    public ResponseEntity<AgentCommonResponse<KnowledgeBaseDtos.KbView>> getKb(
            @PathVariable String kbId,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        return ResponseEntity.ok(AgentCommonResponse.ok(
                ensureRequestId(httpRequest), service.get(userId, kbId)));
    }

    @PatchMapping("/{kbId}")
    public ResponseEntity<AgentCommonResponse<KnowledgeBaseDtos.KbView>> updateKb(
            @PathVariable String kbId,
            @Valid @RequestBody KnowledgeBaseDtos.UpdateKbRequest request,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        return ResponseEntity.ok(AgentCommonResponse.ok(
                ensureRequestId(httpRequest), service.update(userId, kbId, request)));
    }

    @DeleteMapping("/{kbId}")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> deleteKb(
            @PathVariable String kbId,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        service.delete(userId, kbId);
        // Fire-and-forget vector cleanup; orphans wouldn't return wrong
        // answers because the kbId is gone from Java's allowlist.
        ingestionScheduler.scheduleDeletion(userId, kbId, null);
        return ResponseEntity.ok(AgentCommonResponse.ok(
                ensureRequestId(httpRequest), Map.of("deleted", true, "kbId", kbId)));
    }

    @PostMapping("/{kbId}/documents")
    public ResponseEntity<AgentCommonResponse<KnowledgeBaseDtos.DocumentView>> uploadDocument(
            @PathVariable String kbId,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        KnowledgeBaseDtos.DocumentView view = service.uploadDocument(userId, kbId, file);
        // The transactional service method has committed by now, so the
        // async scheduler can read the row safely.
        ingestionScheduler.scheduleIngestion(userId, kbId, view.getDocId());
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), view));
    }

    @GetMapping("/{kbId}/documents")
    public ResponseEntity<AgentCommonResponse<KnowledgeBaseDtos.DocumentListView>> listDocuments(
            @PathVariable String kbId,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        List<KnowledgeBaseDtos.DocumentView> items = service.listDocuments(userId, kbId);
        KnowledgeBaseDtos.DocumentListView view = new KnowledgeBaseDtos.DocumentListView();
        view.setKbId(kbId);
        view.setItems(items);
        view.setTotal(items.size());
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), view));
    }

    @DeleteMapping("/{kbId}/documents/{docId}")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> deleteDocument(
            @PathVariable String kbId,
            @PathVariable String docId,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        service.deleteDocument(userId, kbId, docId);
        ingestionScheduler.scheduleDeletion(userId, kbId, docId);
        return ResponseEntity.ok(AgentCommonResponse.ok(
                ensureRequestId(httpRequest), Map.of("deleted", true, "kbId", kbId, "docId", docId)));
    }

    @GetMapping("/{kbId}/documents/{docId}/status")
    public ResponseEntity<AgentCommonResponse<KnowledgeBaseDtos.DocumentStatusView>> documentStatus(
            @PathVariable String kbId,
            @PathVariable String docId,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        return ResponseEntity.ok(AgentCommonResponse.ok(
                ensureRequestId(httpRequest), service.getDocumentStatus(userId, kbId, docId)));
    }

    private Long requireUserId(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new AgentException(AgentErrorCode.AGENT_AUTH_40101, "JWT missing or invalid");
        }
        return jwtTokenProvider.getUserIdFromToken(auth.substring(7));
    }

    private String ensureRequestId(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }
}
