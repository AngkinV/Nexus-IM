package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic unit fixtures only. T4A does not execute this file; T1 owns validation. */
class McpManagementStoreTest {
    private static final long OWNER = 41;
    private static final String ID = "714e3483-cf38-42e4-88cd-6cf56af62f71";
    private static final String SECRET = "fixture-only-mcp-secret";
    private final ObjectMapper json = new ObjectMapper();
    private final McpServerRepository repository = mock(McpServerRepository.class);
    private final McpCredentialCrypto crypto = new McpCredentialCrypto(Base64.getEncoder().encodeToString(new byte[32]));
    private final McpManagementStore store = new McpManagementStore(repository, json, crypto,
            new com.nexus.chat.util.CredentialAddressPolicy(false));
    private final Map<String, McpServerEntity> rows = new HashMap<>();

    McpManagementStoreTest() {
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            McpServerEntity row = call.getArgument(0);
            rows.put(row.serverId, row);
            return row;
        });
        when(repository.findByServerIdAndOwnerUserIdAndDeletedFalse(anyString(), anyLong())).thenAnswer(call -> {
            McpServerEntity row = rows.get(call.getArgument(0));
            long actor = call.getArgument(1);
            return row != null && row.ownerUserId == actor && !row.deleted ? Optional.of(row) : Optional.empty();
        });
        when(repository.findByServerIdAndOwnerUserId(anyString(), anyLong())).thenAnswer(call -> {
            McpServerEntity row = rows.get(call.getArgument(0));
            long actor = call.getArgument(1);
            return row != null && row.ownerUserId == actor ? Optional.of(row) : Optional.empty();
        });
    }

    private McpServerEntity row(boolean credential) {
        McpServerEntity entity = new McpServerEntity();
        entity.serverId = ID; entity.ownerUserId = OWNER;
        ObjectNode config = json.createObjectNode().put("displayName", "Synthetic service")
                .put("url", "https://mcp.example.invalid/mcp").put("transport", "streamable_http")
                .put("enabled", true).put("timeoutSec", 10).put("resultMaxChars", 8000)
                .put("credentialKind", credential ? "bearer" : "none");
        config.putNull("credentialHeaderName"); config.set("allowedTools", json.createArrayNode());
        entity.configJson = config.toString();
        if (credential) entity.credentialCiphertext = crypto.encrypt(
                json.createObjectNode().put("kind", "bearer").put("secret", SECRET).toString(), OWNER, ID);
        rows.put(ID, entity);
        return entity;
    }

    private ArrayNode catalog() throws Exception {
        ObjectNode tool = json.createObjectNode().put("toolId", ID + ":read_docs").put("serverId", ID)
                .put("remoteName", "read_docs").put("description", "Synthetic documentation")
                .put("name", McpManagementStore.namespace(ID) + "__" + HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest("read_docs".getBytes(StandardCharsets.UTF_8))).substring(0, 26))
                .put("risk", "unclassified").put("allowed", false).put("timeoutSec", 10).put("resultMaxChars", 8000);
        tool.putNull("readOnlyHint");
        tool.set("inputSchema", json.createObjectNode().put("type", "object").set("properties", json.createObjectNode()));
        return json.createArrayNode().add(tool);
    }

    private ObjectNode probe(boolean ok, ArrayNode tools) {
        ObjectNode response = json.createObjectNode().put("serverId", ID).put("configVersion", 1).put("ok", ok)
                .put("catalogValidation", "draft2020-12-local-ref-v1");
        response.set("status", json.createObjectNode().put("state", ok ? "connected" : "error").put("configVersion", 1)
                .put("checkedAt", Instant.now().toString()).put("latencyMs", 0));
        response.set("tools", tools);
        response.put("catalogVersion", McpCatalogDigest.version(tools));
        return response;
    }

    @Test void credentialsAreEncryptedAndPublicViewHasNoSecretOrCiphertext() {
        McpServerEntity entity = row(true);
        assertFalse(entity.configJson.contains(SECRET));
        assertFalse(entity.credentialCiphertext.contains(SECRET));
        assertFalse(store.view(entity).toString().contains(SECRET));
        assertFalse(store.view(entity).has("credential"));
        assertFalse(store.view(entity).has("credentialCiphertext"));
        assertTrue(store.view(entity).path("credentialConfigured").asBoolean());
        assertEquals(SECRET, store.internal(entity).payload().path("credential").path("secret").asText());
        assertFalse(store.internal(entity).toString().contains(SECRET));
    }

    @Test void foreignOwnerCannotReadPatchDeleteOrApplyProbe() {
        row(true);
        assertThrows(ResponseStatusException.class, () -> store.own(OWNER + 1, ID));
        assertThrows(ResponseStatusException.class, () -> store.write(OWNER + 1, ID, json.createObjectNode().put("expectedConfigVersion", 1).put("enabled", false)));
        assertThrows(ResponseStatusException.class, () -> store.delete(OWNER + 1, ID, 1));
        assertThrows(ResponseStatusException.class, () -> store.apply(OWNER + 1, ID, 1, json.createObjectNode(), true));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void updateUsesExpectedVersionAndInvalidatesOldSuccess() {
        McpServerEntity entity = row(false);
        entity.statusJson = json.createObjectNode().put("state", "connected").put("configVersion", 1)
                .put("checkedAt", Instant.now().toString()).toString();
        store.write(OWNER, ID, json.createObjectNode().put("expectedConfigVersion", 1).put("displayName", "New label"));
        assertEquals(2, entity.configVersion);
        assertEquals("unknown", store.view(entity).path("status").path("state").asText());
        assertThrows(ResponseStatusException.class, () -> store.write(OWNER, ID,
                json.createObjectNode().put("expectedConfigVersion", 1).put("enabled", false)));
        assertThrows(ResponseStatusException.class, () -> store.apply(OWNER, ID, 1, json.createObjectNode(), true));
    }

    @Test void failedProbeIsRealErrorNotUnknownOrConnected() {
        McpServerEntity entity = row(false);
        store.apply(OWNER, ID, 1, probe(false, json.createArrayNode()), true);
        assertEquals("error", store.view(entity).path("status").path("state").asText());
        assertFalse(store.view(entity).path("status").path("checkedAt").isNull());
        assertEquals("MCP_PROBE_FAILED", store.view(entity).path("status").path("error").path("code").asText());
    }

    @Test void noHandshakeEvidenceCannotBecomeConnected() {
        McpServerEntity entity = row(false);
        store.apply(OWNER, ID, 1, json.createObjectNode().put("ok", false), true);
        assertEquals("unknown", store.view(entity).path("status").path("state").asText());
        assertTrue(store.view(entity).path("status").path("checkedAt").isNull());
    }

    @Test void catalogDigestAndValidationProofMustMatchBeforePersistence() throws Exception {
        McpServerEntity entity = row(false);
        ObjectNode invalid = probe(true, catalog());
        invalid.put("catalogVersion", "a".repeat(64));
        assertThrows(ResponseStatusException.class, () -> store.apply(OWNER, ID, 1, invalid, true));
        assertNull(entity.catalogJson);
        ObjectNode noProof = probe(true, catalog()); noProof.remove("catalogValidation");
        assertThrows(ResponseStatusException.class, () -> store.apply(OWNER, ID, 1, noProof, true));
        assertNull(entity.catalogJson);
    }

    @Test void discoveredToolsAreNotAutomaticallyApprovedAndLimitsStayCurrent() throws Exception {
        McpServerEntity entity = row(false);
        store.apply(OWNER, ID, 1, probe(true, catalog()), true);
        assertFalse(store.tools(entity).get(0).path("allowed").asBoolean());
        store.write(OWNER, ID, json.createObjectNode().put("expectedConfigVersion", 1)
                .put("timeoutSec", 20).put("resultMaxChars", 256));
        assertEquals(20, store.tools(entity).get(0).path("timeoutSec").asInt());
        assertEquals(256, store.tools(entity).get(0).path("resultMaxChars").asInt());
        assertEquals(2, entity.configVersion);
    }

    @Test void existingAllowlistIsRetainedButConnectionChangeRevokesIt() throws Exception {
        McpServerEntity entity = row(false);
        store.apply(OWNER, ID, 1, probe(true, catalog()), true);
        ObjectNode approve = json.createObjectNode().put("expectedConfigVersion", 1);
        approve.set("allowedTools", json.createArrayNode().add(ID + ":read_docs"));
        store.write(OWNER, ID, approve);
        assertEquals(1, store.view(entity).path("allowedTools").size());
        store.write(OWNER, ID, json.createObjectNode().put("expectedConfigVersion", 2).put("enabled", false));
        assertEquals(1, store.view(entity).path("allowedTools").size());
        store.write(OWNER, ID, json.createObjectNode().put("expectedConfigVersion", 3).put("url", "https://mcp.example.invalid/new"));
        assertEquals(0, store.view(entity).path("allowedTools").size());
        assertNull(entity.catalogJson);
        assertEquals(4, entity.authorizationVersion);
    }

    @Test void tombstoneIsOwnerScopedDisabledAndContainsNoCredential() {
        McpServerEntity entity = row(true);
        store.delete(OWNER, ID, 1);
        assertTrue(entity.deleted);
        assertEquals(2, entity.configVersion);
        assertNull(entity.credentialCiphertext);
        assertFalse(store.internal(entity).payload().path("enabled").asBoolean());
        assertFalse(store.internal(entity).payload().has("credential"));
        assertFalse(store.internal(entity).payload().toString().contains(SECRET));
        assertThrows(ResponseStatusException.class, () -> store.own(OWNER, ID));
        assertSame(entity, store.internalOwn(OWNER, ID));
        assertThrows(ResponseStatusException.class, () -> store.internalOwn(OWNER + 1, ID));
    }
}
