package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.List;

/** BFF operations. Java persists/authorizes; only Python speaks the MCP protocol. */
@Service
public class McpManagementService {
    private final McpManagementStore store;
    private final McpManagementGateway gateway;
    private final ObjectMapper json;

    public McpManagementService(McpManagementStore store, McpManagementGateway gateway, ObjectMapper json) {
        this.store = store;
        this.gateway = gateway;
        this.json = json;
    }

    public List<ObjectNode> list(long actor) {
        return store.list(actor).stream().map(store::view).toList();
    }

    public ObjectNode save(long actor, String trace, String id, ObjectNode patch) {
        McpServerEntity entity = store.write(actor, id, patch);
        // The RPC carries the newly persisted target version, not the old browser CAS version.
        JsonNode result = gateway.call(actor, trace, id == null ? "register" : "update",
                entity.serverId, entity.configVersion, store.internal(entity).payload());
        return store.view(store.apply(actor, entity.serverId, entity.configVersion, result, false));
    }

    public ObjectNode delete(long actor, String trace, String id, long expected) {
        McpServerEntity tombstone = store.delete(actor, id, expected);
        // Owner-scoped, signed tombstone has no credentials and prevents late re-registration.
        gateway.call(actor, trace, "delete", id, tombstone.configVersion, store.internal(tombstone).payload());
        return json.createObjectNode().put("deleted", true).put("serverId", id)
                .put("configVersion", tombstone.configVersion);
    }

    public ObjectNode probe(long actor, String trace, String id, long expected, String operation) {
        McpServerEntity entity = store.own(actor, id);
        store.version(entity, expected);
        JsonNode response = gateway.call(actor, trace, operation, id, expected, store.internal(entity).payload());
        entity = store.apply(actor, id, expected, response, true);
        ObjectNode view = store.view(entity);
        ObjectNode output = json.createObjectNode().put("serverId", id).put("configVersion", expected)
                .put("ok", response.path("ok").asBoolean(false)
                        && "connected".equals(view.path("status").path("state").asText()));
        output.set("status", view.path("status"));
        output.set("catalogVersion", view.path("catalogVersion"));
        output.set("tools", store.tools(entity));
        output.put("toolsCount", store.tools(entity).size());
        return output;
    }

    public ObjectNode tools(long actor, String id) {
        McpServerEntity entity = store.own(actor, id);
        ObjectNode output = json.createObjectNode().put("serverId", id).put("configVersion", entity.configVersion);
        output.set("catalogVersion", store.view(entity).path("catalogVersion"));
        output.set("tools", store.tools(entity));
        return output;
    }

    public ObjectNode status(long actor, String trace, String id) {
        McpServerEntity entity = store.own(actor, id);
        JsonNode response = gateway.call(actor, trace, "status", id, entity.configVersion, store.internal(entity).payload());
        entity = store.applyStatus(actor, id, entity.configVersion, response);
        ObjectNode output = json.createObjectNode().put("serverId", id).put("configVersion", entity.configVersion)
                .put("registrationState", entity.registrationState);
        output.set("status", store.view(entity).path("status"));
        return output;
    }
}
