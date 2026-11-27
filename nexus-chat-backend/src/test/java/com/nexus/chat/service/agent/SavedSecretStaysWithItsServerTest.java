package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.chat.dto.agent.AgentProviderDtos;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.model.ModelCredential;
import com.nexus.chat.repository.ModelCredentialRepository;
import com.nexus.chat.service.CompanionCryptoService;
import com.nexus.chat.util.CredentialAddressPolicy;
import com.nexus.chat.util.ModelEndpointGuard;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A saved model key or MCP credential cannot be carried to another server by editing the
 * entry's address. Anyone signed in to the account can edit an entry, and the stored secret
 * is sent to wherever the entry points, so without this an edit is a way to read the secret.
 */
class SavedSecretStaysWithItsServerTest {
    private static final long USER = 7L;
    private static final long ROW = 31L;

    // ----- model channels -----

    private final ModelCredentialRepository channels = mock(ModelCredentialRepository.class);
    private final CompanionCryptoService modelCrypto = mock(CompanionCryptoService.class);
    private ModelCredential saved;

    SavedSecretStaysWithItsServerTest() {
        // Once: stubbing again would run the first answer with the matcher's empty placeholder.
        when(modelCrypto.encrypt(anyString())).thenAnswer(call -> "enc:" + call.getArgument(0));
        when(modelCrypto.decrypt(anyString())).thenAnswer(call -> call.<String>getArgument(0).substring(4));
    }

    private AgentProviderService channelService(boolean bound, String vendor, String format, String base, boolean withKey) {
        saved = new ModelCredential();
        saved.setId(ROW); saved.setUserId(USER); saved.setProvider(vendor); saved.setVendor(vendor); saved.setApiFormat(format);
        saved.setPurpose(ModelCredential.PURPOSE_CHAT); saved.setDisplayName("Saved"); saved.setBaseUrl(base);
        saved.setDefaultModel("model-a"); saved.setIsDefault(true); saved.setStatus(ModelCredential.CredentialStatus.ok);
        if (withKey) saved.setApiKeyEncrypted("enc:original-key");
        when(channels.findByIdAndUserId(ROW, USER)).thenReturn(Optional.of(saved));
        when(channels.findByUserIdAndProviderAndPurpose(USER, vendor, ModelCredential.PURPOSE_CHAT)).thenReturn(Optional.of(saved));
        when(channels.findByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(anyLong(), anyString())).thenReturn(List.of(saved));
        when(channels.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        return new AgentProviderService(channels, modelCrypto, new ObjectMapper(), mock(ModelEndpointGuard.class),
                mock(PythonModelGateway.class), new CredentialAddressPolicy(bound));
    }

    private static AgentProviderDtos.UpsertProviderRequest edit(String vendor, String format, String base, String key) {
        var request = new AgentProviderDtos.UpsertProviderRequest();
        request.setId(ROW); request.setVendor(vendor); request.setApiFormat(format); request.setDisplayName("Saved");
        request.setBaseUrl(base); request.setDefaultModel("model-a"); request.setApiKey(key);
        return request;
    }

    private void assertChannelRefusedAndUntouched(AgentProviderService service, AgentProviderDtos.UpsertProviderRequest request,
                                                  String vendor, String format, String base) {
        AgentException refused = assertThrows(AgentException.class, () -> service.upsert(USER, request));
        assertEquals(AgentProviderService.KEY_REQUIRED_FOR_NEW_ADDRESS, refused.getDetails().get("reason"));
        assertEquals(base, saved.getBaseUrl(), "the saved address is unchanged");
        assertEquals("enc:original-key", saved.getApiKeyEncrypted(), "the saved key is unchanged");
        assertEquals(vendor, saved.getVendor());
        assertEquals(format, saved.getApiFormat());
        assertEquals(ModelCredential.CredentialStatus.ok, saved.getStatus(), "a refused edit does not even reset the test status");
        verify(channels, never()).saveAndFlush(any());
        verify(channels, never()).save(any());
    }

    @Test void withTheSwitchOffAnAddressChangeKeepsTheKeyAsItAlwaysDid() {
        AgentProviderService service = channelService(false, "custom", "openai-compatible", "https://relay.example.test/v1", true);
        service.upsert(USER, edit("custom", "openai-compatible", "https://elsewhere.example.test/v1", null));
        assertEquals("https://elsewhere.example.test/v1", saved.getBaseUrl());
        assertEquals("enc:original-key", saved.getApiKeyEncrypted());
    }

    @Test void aKeyDoesNotFollowItsChannelToAnotherHostSchemeOrPort() {
        for (String moved : new String[]{"https://elsewhere.example.test/v1", "http://relay.example.test/v1",
                "https://relay.example.test:8443/v1", "https://relay.example.test@elsewhere.example.test/v1"}) {
            AgentProviderService service = channelService(true, "custom", "openai-compatible", "https://relay.example.test/v1", true);
            assertChannelRefusedAndUntouched(service, edit("custom", "openai-compatible", moved, null),
                    "custom", "openai-compatible", "https://relay.example.test/v1");
            org.mockito.Mockito.reset(channels);
        }
    }

    @Test void enteringTheKeyAgainMovesTheChannel() {
        AgentProviderService service = channelService(true, "custom", "openai-compatible", "https://relay.example.test/v1", true);
        service.upsert(USER, edit("custom", "openai-compatible", "https://elsewhere.example.test/v1", "key-for-the-new-server"));
        assertEquals("https://elsewhere.example.test/v1", saved.getBaseUrl());
        assertEquals("enc:key-for-the-new-server", saved.getApiKeyEncrypted());
    }

    @Test void editsThatStayOnTheSameServerKeepTheKey() {
        for (String same : new String[]{"https://relay.example.test/v1", "https://relay.example.test/v2/chat",
                "https://RELAY.example.test:443/v1"}) {
            AgentProviderService service = channelService(true, "custom", "openai-compatible", "https://relay.example.test/v1", true);
            var request = edit("custom", "openai-compatible", same, null);
            request.setDisplayName("Renamed"); request.setDefaultModel("model-b");
            service.upsert(USER, request);
            assertEquals(same, saved.getBaseUrl());
            assertEquals("enc:original-key", saved.getApiKeyEncrypted());
            assertEquals("model-b", saved.getDefaultModel());
        }
    }

    /** A channel with no address of its own goes to its vendor's endpoint, so that is its server. */
    @Test void givingAVendorChannelAnAddressOrAnotherVendorOrProtocolCountsAsMoving() {
        AgentProviderService service = channelService(true, "deepseek", "openai-compatible", null, true);
        assertChannelRefusedAndUntouched(service, edit("deepseek", "openai-compatible", "https://elsewhere.example.test/v1", null),
                "deepseek", "openai-compatible", null);
        org.mockito.Mockito.reset(channels);

        service = channelService(true, "deepseek", "openai-compatible", null, true);
        assertChannelRefusedAndUntouched(service, edit("openai", "openai-compatible", null, null), "deepseek", "openai-compatible", null);
        org.mockito.Mockito.reset(channels);

        service = channelService(true, "deepseek", "openai-compatible", null, true);
        assertChannelRefusedAndUntouched(service, edit("deepseek", "anthropic", null, null), "deepseek", "openai-compatible", null);
        org.mockito.Mockito.reset(channels);

        service = channelService(true, "custom", "openai-compatible", "https://relay.example.test/v1", true);
        assertChannelRefusedAndUntouched(service, edit("custom", "openai-compatible", null, null),
                "custom", "openai-compatible", "https://relay.example.test/v1");
    }

    @Test void aVendorChannelLeftOnItsVendorKeepsTheKey() {
        AgentProviderService service = channelService(true, "deepseek", "openai-compatible", null, true);
        var request = edit("deepseek", "openai-compatible", null, null);
        request.setDefaultModel("model-b");
        service.upsert(USER, request);
        assertEquals("enc:original-key", saved.getApiKeyEncrypted());
        assertEquals("model-b", saved.getDefaultModel());
    }

    @Test void aChannelWithoutASavedKeyMovesFreely() {
        AgentProviderService service = channelService(true, "ollama", "openai-compatible", "https://relay.example.test/v1", false);
        service.upsert(USER, edit("ollama", "openai-compatible", "https://elsewhere.example.test/v1", null));
        assertEquals("https://elsewhere.example.test/v1", saved.getBaseUrl());
        assertNull(saved.getApiKeyEncrypted());
    }

    /** Clients from before channels had ids address a channel by its provider name; same rule. */
    @Test void theOlderRequestShapeThatNamesAChannelByProviderIsHeldToTheSameRule() {
        AgentProviderService service = channelService(true, "custom", null, "https://relay.example.test/v1", true);
        saved.setVendor(null);
        var request = new AgentProviderDtos.UpsertProviderRequest();
        request.setProvider("custom"); request.setBaseUrl("https://elsewhere.example.test/v1"); request.setDefaultModel("model-a");
        AgentException refused = assertThrows(AgentException.class, () -> service.upsert(USER, request));
        assertEquals(AgentProviderService.KEY_REQUIRED_FOR_NEW_ADDRESS, refused.getDetails().get("reason"));
        assertEquals("https://relay.example.test/v1", saved.getBaseUrl());
    }

    // ----- MCP servers -----

    private static final String ID = "714e3483-cf38-42e4-88cd-6cf56af62f71";
    private static final String SECRET = "fixture-only-mcp-secret";
    private final ObjectMapper json = new ObjectMapper();
    private final McpServerRepository servers = mock(McpServerRepository.class);
    private final McpCredentialCrypto mcpCrypto = new McpCredentialCrypto(Base64.getEncoder().encodeToString(new byte[32]));
    private final Map<String, McpServerEntity> rows = new HashMap<>();

    private McpManagementStore mcpStore(boolean bound) {
        when(servers.saveAndFlush(any())).thenAnswer(call -> {
            McpServerEntity row = call.getArgument(0);
            rows.put(row.serverId, row);
            return row;
        });
        when(servers.findByServerIdAndOwnerUserIdAndDeletedFalse(anyString(), anyLong())).thenAnswer(call ->
                Optional.ofNullable(rows.get(call.<String>getArgument(0))));
        return new McpManagementStore(servers, json, mcpCrypto, new CredentialAddressPolicy(bound));
    }

    private McpServerEntity mcpRow(boolean credential) {
        McpServerEntity entity = new McpServerEntity();
        entity.serverId = ID; entity.ownerUserId = USER;
        ObjectNode config = json.createObjectNode().put("displayName", "Synthetic service")
                .put("url", "https://mcp.example.test/mcp").put("transport", "streamable_http")
                .put("enabled", true).put("timeoutSec", 10).put("resultMaxChars", 8000)
                .put("credentialKind", credential ? "bearer" : "none");
        config.putNull("credentialHeaderName"); config.set("allowedTools", json.createArrayNode());
        entity.configJson = config.toString();
        if (credential) entity.credentialCiphertext = mcpCrypto.encrypt(
                json.createObjectNode().put("kind", "bearer").put("secret", SECRET).toString(), USER, ID);
        rows.put(ID, entity);
        return entity;
    }

    private ObjectNode move(String url) {
        return json.createObjectNode().put("expectedConfigVersion", 1).put("url", url);
    }

    private String savedSecret(McpServerEntity entity) throws Exception {
        return json.readTree(mcpCrypto.decrypt(entity.credentialCiphertext, USER, ID)).path("secret").asText();
    }

    @Test void withTheSwitchOffAnMcpAddressChangeKeepsTheCredentialAsItAlwaysDid() throws Exception {
        McpManagementStore store = mcpStore(false);
        McpServerEntity entity = mcpRow(true);
        store.write(USER, ID, move("https://elsewhere.example.test/mcp"));
        assertEquals("https://elsewhere.example.test/mcp", json.readTree(entity.configJson).path("url").asText());
        assertEquals(SECRET, savedSecret(entity));
    }

    @Test void anMcpCredentialDoesNotFollowItsServerToAnotherHostSchemeOrPort() throws Exception {
        for (String moved : new String[]{"https://elsewhere.example.test/mcp", "http://mcp.example.test/mcp",
                "https://mcp.example.test:8443/mcp"}) {
            McpManagementStore store = mcpStore(true);
            McpServerEntity entity = mcpRow(true);
            String configBefore = entity.configJson, cipherBefore = entity.credentialCiphertext;
            long versionBefore = entity.configVersion, authorizationBefore = entity.authorizationVersion;
            ResponseStatusException refused = assertThrows(ResponseStatusException.class, () -> store.write(USER, ID, move(moved)));
            assertEquals("MCP_CREDENTIAL_REQUIRED", refused.getReason(), moved);
            assertEquals(configBefore, entity.configJson);
            assertEquals(cipherBefore, entity.credentialCiphertext);
            assertEquals(versionBefore, entity.configVersion);
            assertEquals(authorizationBefore, entity.authorizationVersion);
            verify(servers, never()).saveAndFlush(any());
            org.mockito.Mockito.reset(servers);
        }
    }

    @Test void anMcpServerMovesWithANewCredentialOrWithTheOldOneCleared() throws Exception {
        McpManagementStore store = mcpStore(true);
        McpServerEntity entity = mcpRow(true);
        ObjectNode withNew = move("https://elsewhere.example.test/mcp");
        withNew.set("credential", json.createObjectNode().put("kind", "bearer").put("secret", "secret-for-the-new-server"));
        store.write(USER, ID, withNew);
        assertEquals("secret-for-the-new-server", savedSecret(entity));

        entity = mcpRow(true);
        store.write(USER, ID, move("https://elsewhere.example.test/mcp").put("clearCredential", true));
        assertNull(entity.credentialCiphertext);
        assertEquals("none", json.readTree(entity.configJson).path("credentialKind").asText());
    }

    @Test void mcpEditsOnTheSameServerAndServersWithoutACredentialAreNotAffected() throws Exception {
        McpManagementStore store = mcpStore(true);
        McpServerEntity entity = mcpRow(true);
        store.write(USER, ID, move("https://MCP.example.test:443/other/path"));
        assertEquals(SECRET, savedSecret(entity));
        assertNotNull(entity.credentialCiphertext);

        entity = mcpRow(true);
        store.write(USER, ID, json.createObjectNode().put("expectedConfigVersion", 1).put("displayName", "Renamed").put("enabled", false));
        assertEquals(SECRET, savedSecret(entity));

        entity = mcpRow(false);
        store.write(USER, ID, move("https://elsewhere.example.test/mcp"));
        assertEquals("https://elsewhere.example.test/mcp", json.readTree(entity.configJson).path("url").asText());
    }
}
