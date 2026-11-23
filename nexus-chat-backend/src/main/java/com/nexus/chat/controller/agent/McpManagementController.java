package com.nexus.chat.controller.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.chat.dto.agent.AgentCommonResponse;
import com.nexus.chat.dto.agent.McpWriteRequest;
import com.nexus.chat.security.JwtTokenProvider;
import com.nexus.chat.service.agent.McpManagementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Browser-facing owner-scoped BFF. No Python/MCP address is exposed to the browser. */
@RestController
@RequestMapping("/api/agent/mcp/servers")
public class McpManagementController {
    private final McpManagementService service;
    private final JwtTokenProvider jwt;
    private final ObjectMapper json;

    public McpManagementController(McpManagementService service, JwtTokenProvider jwt, ObjectMapper json) {
        this.service = service;
        this.jwt = jwt;
        this.json = json;
    }

    private long actor(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) throw unauthorized();
        try {
            String token = authorization.substring(7);
            if (!jwt.validateToken(token)) throw unauthorized();
            Long id = jwt.getUserIdFromToken(token);
            if (id == null || id <= 0) throw unauthorized();
            return id;
        } catch (Exception ignored) { throw unauthorized(); }
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "MCP_AUTH_REQUIRED");
    }

    private String trace() { return "tr_" + UUID.randomUUID().toString().replace("-", ""); }

    private McpWriteRequest body(HttpServletRequest request) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(65537);
            if (bytes.length > 65536) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "MCP_SIZE_LIMIT");
            JsonNode value = json.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            if (!(value instanceof ObjectNode object)) throw new IllegalArgumentException();
            return new McpWriteRequest(object);
        } catch (ResponseStatusException controlled) { throw controlled; }
        catch (Exception ignored) {
            // JSON errors must not echo a write-only credential or raw parser exception.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MCP_INVALID_REQUEST");
        }
    }

    private long expected(McpWriteRequest request) {
        ObjectNode value = request.payload();
        if (value.size() != 1 || !value.path("expectedConfigVersion").isIntegralNumber()
                || !value.path("expectedConfigVersion").canConvertToLong()
                || value.path("expectedConfigVersion").asLong() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MCP_EXPECTED_VERSION_REQUIRED");
        }
        return value.path("expectedConfigVersion").asLong();
    }

    private AgentCommonResponse<Object> ok(String trace, Object value) { return AgentCommonResponse.ok(trace, value); }

    @GetMapping
    public AgentCommonResponse<Object> list(HttpServletRequest request) {
        long owner = actor(request);
        return ok(trace(), service.list(owner));
    }

    @PostMapping
    public AgentCommonResponse<Object> create(HttpServletRequest request) {
        long owner = actor(request);
        String trace = trace();
        return ok(trace, service.save(owner, trace, null, body(request).payload()));
    }

    @PatchMapping("/{id}")
    public AgentCommonResponse<Object> patch(@PathVariable String id, HttpServletRequest request) {
        long owner = actor(request);
        String trace = trace();
        return ok(trace, service.save(owner, trace, id, body(request).payload()));
    }

    @DeleteMapping("/{id}")
    public AgentCommonResponse<Object> delete(@PathVariable String id, HttpServletRequest request) {
        long owner = actor(request);
        String trace = trace();
        return ok(trace, service.delete(owner, trace, id, expected(body(request))));
    }

    @PostMapping("/{id}/test")
    public AgentCommonResponse<Object> test(@PathVariable String id, HttpServletRequest request) {
        long owner = actor(request);
        String trace = trace();
        return ok(trace, service.probe(owner, trace, id, expected(body(request)), "test"));
    }

    @PostMapping("/{id}/tools/refresh")
    public AgentCommonResponse<Object> refresh(@PathVariable String id, HttpServletRequest request) {
        long owner = actor(request);
        String trace = trace();
        return ok(trace, service.probe(owner, trace, id, expected(body(request)), "refresh"));
    }

    @GetMapping("/{id}/tools")
    public AgentCommonResponse<Object> tools(@PathVariable String id, HttpServletRequest request) {
        return ok(trace(), service.tools(actor(request), id));
    }

    @GetMapping("/{id}/status")
    public AgentCommonResponse<Object> status(@PathVariable String id, HttpServletRequest request) {
        long owner = actor(request);
        String trace = trace();
        return ok(trace, service.status(owner, trace, id));
    }
}
