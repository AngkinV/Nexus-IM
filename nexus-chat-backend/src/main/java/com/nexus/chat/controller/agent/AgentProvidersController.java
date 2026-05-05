package com.nexus.chat.controller.agent;

import com.nexus.chat.dto.agent.AgentCommonResponse;
import com.nexus.chat.dto.agent.AgentProviderDtos;
import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.security.JwtTokenProvider;
import com.nexus.chat.service.agent.AgentProviderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-user CRUD for the LLM providers backing the AI Assistant.
 * The frontend's "AI 设置" panel is the only consumer.
 */
@RestController
@RequestMapping("/api/agent/providers")
@RequiredArgsConstructor
public class AgentProvidersController {

    private final AgentProviderService providerService;
    private final JwtTokenProvider jwtTokenProvider;

    @GetMapping
    public ResponseEntity<AgentCommonResponse<List<AgentProviderDtos.ProviderView>>> list(
            @RequestParam(value = "purpose", required = false) String purpose,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        List<AgentProviderDtos.ProviderView> views = (purpose == null || purpose.isBlank())
                ? providerService.listForUser(userId)
                : providerService.listForUserByPurpose(userId, purpose);
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), views));
    }

    @PostMapping
    public ResponseEntity<AgentCommonResponse<AgentProviderDtos.ProviderView>> upsert(
            @Valid @RequestBody AgentProviderDtos.UpsertProviderRequest request,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), providerService.upsert(userId, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<AgentCommonResponse<Map<String, Object>>> delete(
            @PathVariable Long id,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        providerService.delete(userId, id);
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), Map.of("deleted", true)));
    }

    @PostMapping("/{id}/default")
    public ResponseEntity<AgentCommonResponse<AgentProviderDtos.ProviderView>> setDefault(
            @PathVariable Long id,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), providerService.setDefault(userId, id)));
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<AgentCommonResponse<AgentProviderDtos.ProviderTestResult>> test(
            @PathVariable Long id,
            HttpServletRequest httpRequest
    ) {
        Long userId = requireUserId(httpRequest);
        return ResponseEntity.ok(AgentCommonResponse.ok(ensureRequestId(httpRequest), providerService.test(userId, id)));
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
