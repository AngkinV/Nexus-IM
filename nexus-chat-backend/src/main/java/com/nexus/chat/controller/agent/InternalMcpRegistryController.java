package com.nexus.chat.controller.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.chat.service.agent.McpInternalRequestVerifier;
import com.nexus.chat.service.agent.McpManagementStore;
import com.nexus.chat.service.agent.McpServerEntity;
import com.nexus.chat.service.agent.McpSecretRegistryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Data-only authority read for Python. Never accessible with a browser JWT alone. */
@RestController
@RequestMapping("/internal/agent/mcp-management")
public class InternalMcpRegistryController {
    private final McpInternalRequestVerifier verifier;
    private final McpManagementStore store;
    private final ObjectMapper json;
    private final McpSecretRegistryService secrets;

    public InternalMcpRegistryController(McpInternalRequestVerifier verifier, McpManagementStore store, ObjectMapper json, McpSecretRegistryService secrets) {
        this.verifier = verifier;
        this.store = store;
        this.json = json;
        this.secrets = secrets;
    }

    private ObjectNode record(McpServerEntity entity, McpSecretRegistryService.ProviderSecrets providerSecrets) {
        ObjectNode value = store.internal(entity).payload();
        value.put("forbiddenProviderSecretsVerified", !entity.deleted && providerSecrets != null);
        value.set("forbiddenProviderSecrets", providerSecrets == null ? json.createArrayNode() : providerSecrets.payload());
        return value;
    }

    @PostMapping("/registry")
    public ObjectNode registry(HttpServletRequest request) {
        McpInternalRequestVerifier.Verified caller = verifier.verify(request);
        ObjectNode result = json.createObjectNode();
        if (caller.operation().equals("get")) {
            // A real owner-scoped, disabled tombstone is the deletion proof; no secret survives it.
            McpServerEntity entity = store.internalOwn(caller.actorUserId(), caller.serverId());
            result.set("server", record(entity, entity.deleted ? null : secrets.owned(caller.actorUserId())));
        } else {
            ArrayNode records = json.createArrayNode();
            int count = 0;
            long bytes = 0;
            var owned = store.list(caller.actorUserId());
            var providerSecrets = owned.isEmpty() ? null : secrets.owned(caller.actorUserId());
            for (McpServerEntity entity : owned) {
                if (++count > 128) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "MCP_SIZE_LIMIT");
                ObjectNode record = record(entity, providerSecrets);
                try { bytes += json.writeValueAsBytes(record).length; }
                catch (Exception ignored) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MCP_REGISTRY_UNAVAILABLE"); }
                if (bytes > 2000000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "MCP_SIZE_LIMIT");
                records.add(record);
            }
            result.set("servers", records);
        }
        return result;
    }
}
