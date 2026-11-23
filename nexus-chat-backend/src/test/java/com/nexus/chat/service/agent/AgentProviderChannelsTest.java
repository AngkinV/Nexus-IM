package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.dto.agent.AgentProviderDtos;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.model.ModelChannels;
import com.nexus.chat.model.ModelCredential;
import com.nexus.chat.repository.ModelCredentialRepository;
import com.nexus.chat.service.CompanionCryptoService;
import com.nexus.chat.util.ModelEndpointGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * One account, many channels. The repository is an in-memory table that enforces the
 * same (user, provider, purpose) key as the database, so an overwrite or a duplicate
 * id fails here the way it would against MySQL.
 */
class AgentProviderChannelsTest {
    private static final long USER = 7L;
    private final List<ModelCredential> table = new ArrayList<>();
    private final ModelCredentialRepository repo = mock(ModelCredentialRepository.class);
    private final CompanionCryptoService crypto = mock(CompanionCryptoService.class);
    private final AgentProviderService service = new AgentProviderService(
            repo, crypto, new ObjectMapper(), mock(ModelEndpointGuard.class), mock(PythonModelGateway.class));
    private long nextId = 1;

    @BeforeEach void table() {
        when(crypto.encrypt(anyString())).thenAnswer(call -> "enc:" + call.getArgument(0));
        when(crypto.decrypt(anyString())).thenAnswer(call -> call.<String>getArgument(0).substring(4));
        when(repo.findByUserIdAndProviderAndPurpose(anyLong(), anyString(), anyString())).thenAnswer(call -> table.stream()
                .filter(c -> c.getUserId().equals(call.getArgument(0)) && c.getProvider().equals(call.getArgument(1))
                        && c.getPurpose().equals(call.getArgument(2))).findFirst());
        when(repo.existsByUserIdAndProviderAndPurpose(anyLong(), anyString(), anyString())).thenAnswer(call ->
                repo.findByUserIdAndProviderAndPurpose(call.getArgument(0), call.getArgument(1), call.getArgument(2)).isPresent());
        when(repo.findByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(anyLong(), anyString())).thenAnswer(call -> table.stream()
                .filter(c -> c.getUserId().equals(call.getArgument(0)) && c.getPurpose().equals(call.getArgument(1))).toList());
        when(repo.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(anyLong())).thenAnswer(call -> table.stream()
                .filter(c -> c.getUserId().equals(call.getArgument(0))).toList());
        when(repo.countByUserIdAndPurpose(anyLong(), anyString())).thenAnswer(call -> (long)
                repo.findByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(call.getArgument(0), call.getArgument(1)).size());
        when(repo.findByIdAndUserId(anyLong(), anyLong())).thenAnswer(call -> table.stream()
                .filter(c -> c.getId().equals(call.getArgument(0)) && c.getUserId().equals(call.getArgument(1))).findFirst());
        when(repo.findFirstByUserIdAndPurposeAndIsDefaultTrue(anyLong(), anyString())).thenAnswer(call -> table.stream()
                .filter(c -> c.getUserId().equals(call.getArgument(0)) && c.getPurpose().equals(call.getArgument(1))
                        && Boolean.TRUE.equals(c.getIsDefault())).findFirst());
        when(repo.save(any())).thenAnswer(call -> store(call.getArgument(0)));
        when(repo.saveAndFlush(any())).thenAnswer(call -> store(call.getArgument(0)));
    }

    private ModelCredential store(ModelCredential row) {
        if (row.getId() == null) {
            assertTrue(table.stream().noneMatch(c -> c.getUserId().equals(row.getUserId())
                    && c.getProvider().equals(row.getProvider()) && c.getPurpose().equals(row.getPurpose())),
                    "unique key (user, provider, purpose) violated by " + row.getProvider());
            row.setId(nextId++);
            table.add(row);
        }
        return row;
    }

    private static AgentProviderDtos.UpsertProviderRequest channel(String vendor, String format, String name, String base, String key) {
        var req = new AgentProviderDtos.UpsertProviderRequest();
        req.setVendor(vendor); req.setApiFormat(format); req.setDisplayName(name); req.setBaseUrl(base);
        req.setApiKey(key); req.setDefaultModel("model-of-" + name);
        return req;
    }

    @Test void addingChannelsNeverReplacesAnExistingOne() {
        var first = service.upsert(USER, channel("custom", "openai-compatible", "Relay A", "https://a.invalid/v1", "key-a"));
        var second = service.upsert(USER, channel("custom", "openai-compatible", "Relay B", "https://b.invalid/v1", "key-b"));
        var third = service.upsert(USER, channel("custom", "anthropic", "Relay C", "https://c.invalid", "key-c"));
        var official = service.upsert(USER, channel("deepseek", "openai-compatible", "DeepSeek", "https://api.deepseek.com", "key-d"));

        assertEquals(List.of("custom", "custom-2", "custom-3", "deepseek"),
                List.of(first.getProvider(), second.getProvider(), third.getProvider(), official.getProvider()));
        assertEquals(4, service.listForUser(USER).size());
        ModelCredential a = table.get(0);
        assertEquals("Relay A", a.getDisplayName());
        assertEquals("https://a.invalid/v1", a.getBaseUrl());
        assertEquals("enc:key-a", a.getApiKeyEncrypted(), "the first relay keeps its own key");
        assertTrue(first.isDefault(), "the first channel of a purpose becomes the default");
        assertFalse(second.isDefault());
    }

    @Test void aClientSuppliedIdThatIsTakenGetsAFreeOne() {
        var req = channel("openai", "openai-compatible", "Main", "https://api.openai.com/v1", "k1");
        req.setProvider("openai");
        service.upsert(USER, req);
        var again = channel("openai", "openai-compatible", "Second account", "https://api.openai.com/v1", "k2");
        again.setProvider("openai");
        assertEquals("openai-2", service.upsert(USER, again).getProvider());
        assertEquals("enc:k1", table.get(0).getApiKeyEncrypted());
    }

    @Test void editingAddressesTheChannelByIdAndKeepsItsKey() {
        var saved = service.upsert(USER, channel("custom", "openai-compatible", "Relay A", "https://a.invalid/v1", "key-a"));
        service.upsert(USER, channel("custom", "openai-compatible", "Relay B", "https://b.invalid/v1", "key-b"));

        var edit = channel("custom", "openai-compatible", "Relay A renamed", "https://a.invalid/v1", null);
        edit.setId(saved.getId());
        edit.setProvider("something-else");
        var view = service.upsert(USER, edit);

        assertEquals(saved.getId(), view.getId());
        assertEquals("custom", view.getProvider(), "a saved channel id is fixed");
        assertEquals("Relay A renamed", view.getDisplayName());
        assertEquals("enc:key-a", table.get(0).getApiKeyEncrypted(), "a blank key keeps the stored one");
        assertEquals(2, table.size());
    }

    @Test void anotherAccountsChannelCannotBeEdited() {
        var saved = service.upsert(USER, channel("custom", "openai-compatible", "Mine", "https://a.invalid/v1", "key-a"));
        var edit = channel("custom", "openai-compatible", "Taken over", "https://evil.invalid/v1", "key-x");
        edit.setId(saved.getId());
        assertThrows(AgentException.class, () -> service.upsert(8L, edit));
        assertEquals("Mine", table.get(0).getDisplayName());
        assertEquals(1, table.size());
    }

    @Test void aChatChannelIsNotEditableAsAnEmbeddingChannel() {
        var saved = service.upsert(USER, channel("openai", "openai-compatible", "Chat", "https://api.openai.com/v1", "k"));
        var edit = channel("openai", "openai-compatible", "Chat", "https://api.openai.com/v1", null);
        edit.setId(saved.getId());
        edit.setPurpose("embedding");
        assertThrows(AgentException.class, () -> service.upsert(USER, edit));
    }

    @Test void aClientWithoutVendorsStillUpdatesByProviderId() {
        var legacy = new AgentProviderDtos.UpsertProviderRequest();
        legacy.setProvider("openai"); legacy.setBaseUrl("https://api.openai.com/v1"); legacy.setApiKey("k1"); legacy.setDefaultModel("m1");
        var created = service.upsert(USER, legacy);
        legacy.setApiKey(null); legacy.setDefaultModel("m2");
        var updated = service.upsert(USER, legacy);
        assertEquals(created.getId(), updated.getId());
        assertEquals("m2", updated.getDefaultModel());
        assertEquals(1, table.size());
    }

    @Test void makingAChannelDefaultClearsThePreviousDefaultOnly() {
        var a = service.upsert(USER, channel("custom", "openai-compatible", "A", "https://a.invalid/v1", "k"));
        var req = channel("custom", "openai-compatible", "B", "https://b.invalid/v1", "k");
        req.setMakeDefault(true);
        var b = service.upsert(USER, req);
        var embedding = channel("openai", "openai-compatible", "E", "https://api.openai.com/v1", "k");
        embedding.setPurpose("embedding");
        var e = service.upsert(USER, embedding);

        assertTrue(b.isDefault());
        assertFalse(table.get(0).getIsDefault(), "A stopped being the chat default");
        assertTrue(e.isDefault(), "the embedding default is a separate choice");
        assertEquals(a.getId(), table.get(0).getId());
    }

    @Test void theAdapterFollowsTheSavedProtocolNotTheChannelId() {
        service.upsert(USER, channel("anthropic", "anthropic", "Claude", "https://api.anthropic.com", "k"));
        var second = service.upsert(USER, channel("anthropic", "anthropic", "Claude team", "https://api.anthropic.com", "k"));
        var relay = service.upsert(USER, channel("custom", "anthropic", "Claude relay", "https://relay.invalid", "k"));
        var gemini = service.upsert(USER, channel("gemini", "openai-compatible", "Gemini (OpenAI API)",
                "https://generativelanguage.googleapis.com/v1beta/openai", "k"));
        var deepseek = service.upsert(USER, channel("deepseek", "openai-compatible", "DeepSeek", "https://api.deepseek.com", "k"));
        var unknown = service.upsert(USER, channel("openrouter", "openai-compatible", "OpenRouter", "https://openrouter.ai/api/v1", "k"));

        assertEquals("anthropic-2", second.getProvider());
        assertEquals("anthropic", service.resolveForRequest(USER, second.getId()).orElseThrow().provider());
        assertEquals("anthropic", service.resolveForRequest(USER, relay.getId()).orElseThrow().provider());
        assertEquals("openai-compatible", service.resolveForRequest(USER, gemini.getId()).orElseThrow().provider());
        assertEquals("deepseek", service.resolveForRequest(USER, deepseek.getId()).orElseThrow().provider());
        assertEquals("openai-compatible", service.resolveForRequest(USER, unknown.getId()).orElseThrow().provider());
    }

    @Test void channelsSavedBeforeProtocolsBehaveAsTheyAlwaysDid() {
        for (String id : List.of("openai", "custom", "claude", "google", "deepseek-personal", "ollama")) {
            ModelCredential row = new ModelCredential();
            row.setUserId(USER); row.setProvider(id); row.setBaseUrl("https://x.invalid/v1"); row.setDefaultModel("m");
            if (!id.equals("ollama")) row.setApiKeyEncrypted("enc:k");
            store(row);
        }
        for (ModelCredential row : table) {
            assertEquals(row.getProvider(), service.resolveForRequest(USER, row.getId()).orElseThrow().provider());
        }
        assertEquals(ModelChannels.ANTHROPIC, ModelChannels.apiFormat(table.get(2)));
        assertEquals(ModelChannels.GEMINI, ModelChannels.apiFormat(table.get(3)));
        assertEquals(ModelChannels.OPENAI_COMPATIBLE, ModelChannels.apiFormat(table.get(4)));
        assertTrue(ModelChannels.keyOptional(table.get(5)));
        assertFalse(ModelChannels.keyOptional(table.get(4)));
    }

    @Test void onlyALocalServerMayRunWithoutAKey() {
        var local = service.upsert(USER, channel("lmstudio", "openai-compatible", "LM Studio", "http://localhost:1234/v1", null));
        var remote = service.upsert(USER, channel("custom", "openai-compatible", "Relay", "https://a.invalid/v1", null));

        assertTrue(local.isKeyOptional());
        assertEquals("ollama", service.resolveForRequest(USER, local.getId()).orElseThrow().provider(),
                "the keyless adapter name the Python service accepts");
        assertFalse(remote.isKeyOptional());
        assertThrows(AgentException.class, () -> service.resolveForRequest(USER, remote.getId()));
    }

    @Test void savingAnUnchangedLegacyChannelKeepsItsTestResult() {
        ModelCredential row = new ModelCredential();
        row.setUserId(USER); row.setProvider("openai"); row.setBaseUrl("https://api.openai.com/v1"); row.setDefaultModel("m");
        row.setApiKeyEncrypted("enc:k"); row.setStatus(ModelCredential.CredentialStatus.ok);
        store(row);

        var edit = channel("openai", "openai-compatible", "OpenAI", "https://api.openai.com/v1", null);
        edit.setId(row.getId()); edit.setDefaultModel("m");
        assertEquals("ok", service.upsert(USER, edit).getStatus(), "naming the protocol it already used is not a change");

        edit.setApiFormat("anthropic");
        assertEquals("unknown", service.upsert(USER, edit).getStatus(), "a different protocol must be tested again");
    }

    @Test void anUnknownProtocolIsRefused() {
        assertThrows(AgentException.class, () ->
                service.upsert(USER, channel("custom", "grpc", "Odd", "https://a.invalid/v1", "k")));
        assertTrue(table.isEmpty());
    }
}
