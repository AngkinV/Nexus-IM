package com.nexus.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.model.CompanionMessage;
import com.nexus.chat.model.CompanionModelBinding;
import com.nexus.chat.model.CompanionRole;
import com.nexus.chat.model.ModelCredential;
import com.nexus.chat.service.agent.AgentProviderService.ResolvedProvider;
import com.nexus.chat.service.agent.PythonModelGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** At the gateway boundary: no supplier, socket, environment credential or database. */
class CompanionModelServiceTest {
    private static final long OWNER = 7;
    private static final String BASE = "https://fixture.invalid/custom-root/";
    private static final String STATE = "<state>{\"mood\":\"warm\"}</state>\n";
    private final PythonModelGateway gateway = mock(PythonModelGateway.class);
    private final CompanionCryptoService crypto = mock(CompanionCryptoService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final CompanionModelService service = new CompanionModelService(crypto, gateway, 23, 1777);
    private final ModelCredential credential = new ModelCredential();
    private final CompanionModelBinding binding = new CompanionModelBinding();
    private final CompanionRole role = new CompanionRole();
    private final CompanionMessage user = new CompanionMessage();

    @BeforeEach void setup() {
        role.setName("旅人");
        binding.setUserId(OWNER);
        binding.setProvider("gemini");
        binding.setModelName("role-model");
        binding.setTemperature(.65);
        credential.setId(42L);
        credential.setUserId(OWNER);
        credential.setProvider("gemini");
        credential.setDisplayName("Fixture channel");
        credential.setBaseUrl(BASE);
        credential.setDefaultModel("channel-model");
        credential.setApiKeyEncrypted("test-ciphertext");
        when(crypto.decrypt("test-ciphertext")).thenReturn("test-key");
        user.setSenderType(CompanionMessage.SenderType.user);
        user.setContent("你好。");
    }

    @Test void blockingReplyUsesCredentialOwnerStoredChannelRolePromptAndSampling() {
        when(gateway.complete(eq(OWNER), eq(cfg("role-model")), argThat(messages ->
                messages.size() == 2 && messages.get(0).get("content").contains("你是旅人")
                        && messages.get(0).get("content").contains("<state>")
                        && messages.get(1).equals(Map.of("role", "user", "content", "你好。"))),
                eq(.65), eq(1777), eq(23))).thenReturn(envelope(STATE + "坐吧。"));
        var result = service.generateReply(context(), List.of(user), binding, credential);
        assertTrue(result.success());
        assertEquals(STATE + "坐吧。", result.content());
        assertNull(result.error());
    }

    @ParameterizedTest @ValueSource(strings = {"", "   ", "<state>{\"mood\":\"warm\"}</state>\n"})
    void probeRequiresVisibleTextEvenWithSuccessfulCompletion(String raw) {
        probeResult(raw);
        var reply = service.probe(context(), List.of(user), binding, credential);
        assertFalse(reply.success());
        assertEquals("EMPTY_CONTENT", reply.error().path("code").asText());
    }

    @Test void probeUsesRoleReplyParametersAndReturnsOnlyVisibleText() {
        probeResult(STATE + "坐吧。");
        var reply = service.probe(context(), List.of(user), binding, credential);
        assertTrue(reply.success());
        assertEquals("坐吧。", reply.content());
    }

    @Test void missingStoredModelFailsWithoutGuessingOrCallingPython() {
        binding.setModelName("   ");
        credential.setDefaultModel(null);
        var result = service.generateReply(context(), List.of(user), binding, credential);
        assertFalse(result.success());
        assertNotNull(result.error());
        verifyNoInteractions(gateway);
    }

    @Test void noRoleOverrideUsesOnlyTheSavedModelOnTheSameChannel() {
        binding.setModelName(null);
        when(gateway.complete(eq(OWNER), eq(cfg("channel-model")), anyList(), eq(.65), eq(1777), eq(23)))
                .thenReturn(envelope("来了。"));
        assertEquals("来了。", service.generateReply(context(), List.of(user), binding, credential).content());
    }

    @Test void keylessLocalChannelDoesNotBorrowAnotherCredential() {
        binding.setProvider("ollama");
        credential.setProvider("ollama");
        credential.setApiKeyEncrypted(null);
        when(gateway.complete(eq(OWNER), argThat(c -> c.provider().equals("ollama") && c.apiKeyPlain() == null),
                anyList(), anyDouble(), anyInt(), anyInt())).thenReturn(envelope("你好"));
        assertTrue(service.generateReply(context(), List.of(user), binding, credential).success());
        verifyNoInteractions(crypto);
    }

    @Test void nonStopResponseCannotBeTreatedAsCompleted() {
        var raw = mapper.createObjectNode().put("text", "partial").put("finishReason", "length");
        var outcome = CompanionModelService.ModelReply.from(raw);
        assertFalse(outcome.success());
        assertTrue(outcome.partial());
    }

    @Test void aMismatchedBindingCannotConstructACallEvenIfPassedToModelServiceDirectly() {
        binding.setEndpoint("https://old.invalid/root");
        assertFalse(service.generateReply(context(), List.of(user), binding, credential).success());
        binding.setEndpoint(null);
        binding.setProvider("other");
        assertFalse(service.generateReply(context(), List.of(user), binding, credential).success());
        verifyNoInteractions(gateway);
    }

    @Test void preservesTypedErrorAndAlreadyStreamedDeltasWithoutCallingCompletionAgain() {
        var rate = mapper.createObjectNode().put("code", "RATE_LIMIT").put("summary", "wait")
                .put("httpStatus", 429).put("upstreamCode", "slow_down").put("retryAfterSeconds", 5);
        when(gateway.stream(eq(OWNER), eq(cfg("role-model")), anyList(), eq(.65), eq(1777), eq(23), any()))
                .thenAnswer(inv -> {
                    Consumer<String> out = inv.getArgument(6);
                    out.accept(STATE);
                    out.accept("半句");
                    return mapper.createObjectNode().put("text", STATE + "半句").put("finishReason", "error")
                            .put("partial", true).set("error", rate);
                });
        var deltas = new ArrayList<String>();
        var reply = service.streamReply(context(), List.of(user), binding, credential, deltas::add);
        assertEquals(List.of(STATE, "半句"), deltas);
        assertEquals(STATE + "半句", reply.content());
        assertTrue(reply.partial());
        assertFalse(reply.success());
        assertEquals(rate, reply.error());
        verify(gateway, never()).complete(anyLong(), any(), anyList(), anyDouble(), anyInt(), anyInt());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void callbackExceptionUnwindsTheStreamImmediately(boolean readerGone) {
        RuntimeException failure = readerGone ? new CompanionModelService.ReaderGone() : new IllegalStateException("consumer");
        int[] delivered = {0};
        when(gateway.stream(anyLong(), any(), anyList(), anyDouble(), anyInt(), anyInt(), any())).thenAnswer(inv -> {
            Consumer<String> out = inv.getArgument(6);
            out.accept("第一段");
            out.accept("第二段");
            fail("callback must abort the read, not be treated as a model error");
            return null;
        });
        assertSame(failure, assertThrows(RuntimeException.class, () -> service.streamReply(
                context(), List.of(user), binding, credential, piece -> {
                    if (++delivered[0] == 2) throw failure;
                })));
        assertEquals(2, delivered[0]);
    }

    @Test void voiceClosingAndGuestUseTheSameChannelAndKeepTheirExistingFormats() {
        when(gateway.complete(eq(OWNER), eq(cfg("role-model")), anyList(), eq(.9), eq(400), eq(23)))
                .thenReturn(envelope("（我想留下。）"));
        when(gateway.complete(eq(OWNER), eq(cfg("role-model")), anyList(), eq(.8), eq(300), eq(8)))
                .thenReturn(envelope("再见。===他们在雨中道别。"));
        when(gateway.complete(eq(OWNER), eq(cfg("role-model")), anyList(), eq(.95), eq(400), eq(8)))
                .thenReturn(envelope("名字：木叶\n性格：坦诚\n语气：短句\n开场：你好。"));
        assertEquals("（我想留下。）", service.generateInnerVoice(role, List.of(user), binding, credential, null, 20).content());
        assertEquals(new CompanionModelService.ClosingReply("再见。", "他们在雨中道别。"),
                service.generateClosing(role, List.of(user), binding, credential, "late-rain"));
        var guest = service.generateRole(binding, credential, "empty-bar");
        assertEquals("木叶", guest.name());
        assertEquals("你好。", guest.greeting());
    }

    private void probeResult(String content) {
        when(gateway.complete(eq(OWNER), eq(cfg("role-model")), argThat(messages ->
                messages.get(0).get("content").contains("<state>")
                        && messages.get(1).equals(Map.of("role", "user", "content", "你好。"))),
                eq(.65), eq(1777), eq(23))).thenReturn(envelope(content));
    }

    private JsonNode envelope(String text) {
        return mapper.createObjectNode().put("text", text).put("finishReason", "stop").put("partial", false).putNull("error");
    }

    private ResolvedProvider cfg(String model) {
        return new ResolvedProvider(42L, "gemini", "Fixture channel", BASE, model, "test-key");
    }

    private CompanionPromptBuilder.Context context() {
        return new CompanionPromptBuilder.Context(role, "empty-bar", 95, null, null, List.of(), true);
    }
}
