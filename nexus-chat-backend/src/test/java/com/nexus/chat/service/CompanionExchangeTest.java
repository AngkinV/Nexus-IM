package com.nexus.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexus.chat.dto.CompanionChatResponse;
import com.nexus.chat.dto.CompanionMessageDTO;
import com.nexus.chat.dto.CompanionStatusDTO;
import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import com.nexus.chat.util.CompanionStateTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The bookkeeping around one exchange, with the model and the database mocked. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanionExchangeTest {

    private static final long USER = 7L;
    private static final long ROLE = 3L;

    @Mock CompanionRoleRepository roleRepository;
    @Mock CompanionGrowthRepository growthRepository;
    @Mock CompanionMemoryRepository memoryRepository;
    @Mock CompanionConversationRepository conversationRepository;
    @Mock CompanionMessageRepository messageRepository;
    @Mock CompanionModelBindingRepository modelBindingRepository;
    @Mock ModelCredentialRepository credentialRepository;
    @Mock CompanionStatusRepository statusRepository;
    @Mock CompanionModelService modelService;
    @Mock CompanionCryptoService cryptoService;
    @Mock TavernSessionRepository sessionRepository;
    @Mock CompanionModelResolver modelResolver;

    private CompanionService service;
    private CompanionRole role;
    private final List<CompanionMessage> saved = new ArrayList<>();
    private long nextId = 100;

    @BeforeEach
    void setUp() {
        service = new CompanionService(roleRepository, growthRepository, memoryRepository,
                conversationRepository, messageRepository, modelBindingRepository, credentialRepository,
                statusRepository, new CompanionFallbackService(), modelService, cryptoService,
                sessionRepository, modelResolver);

        role = new CompanionRole();
        role.setId(ROLE);
        role.setUserId(USER);
        role.setName("艾瑞斯");
        role.setTone("柔和");
        when(roleRepository.findByIdAndUserId(ROLE, USER)).thenReturn(Optional.of(role));

        CompanionConversation conversation = new CompanionConversation();
        conversation.setId(50L);
        conversation.setUserId(USER);
        conversation.setRoleId(ROLE);
        when(conversationRepository.findByUserIdAndRoleId(USER, ROLE)).thenReturn(Optional.of(conversation));

        when(messageRepository.save(any(CompanionMessage.class))).thenAnswer(inv -> {
            CompanionMessage m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId(nextId++);
                saved.add(m);
            }
            return m;
        });
        when(sessionRepository.save(any(TavernSession.class))).thenAnswer(inv -> {
            TavernSession s = inv.getArgument(0);
            if (s.getId() == null) s.setId(900L);
            return s;
        });
        when(sessionRepository.findFirstByUserIdAndRoleIdAndStatusOrderByStartedAtDesc(
                USER, ROLE, TavernSession.Status.active)).thenReturn(Optional.empty());
        when(memoryRepository.findByUserIdAndRoleIdOrderByCreatedAtDesc(USER, ROLE)).thenReturn(List.of());
        when(growthRepository.findByUserIdAndRoleId(USER, ROLE)).thenReturn(Optional.empty());
        when(statusRepository.findByUserIdAndRoleId(USER, ROLE)).thenReturn(Optional.empty());
        when(statusRepository.save(any(CompanionStatus.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void modelConfigured() {
        CompanionModelBinding binding = new CompanionModelBinding();
        binding.setRoleId(ROLE);
        binding.setUserId(USER);
        binding.setProvider("role-channel");
        binding.setEndpoint("https://example.invalid/v1");
        binding.setModelName("test-model");
        ModelCredential credential = new ModelCredential();
        credential.setId(17L);
        credential.setUserId(USER);
        credential.setProvider("role-channel");
        credential.setBaseUrl("https://example.invalid/v1");
        credential.setDefaultModel("channel-model");
        credential.setApiKeyEncrypted("fixture-ciphertext");
        when(modelResolver.resolve(USER, ROLE))
                .thenReturn(new CompanionModelResolver.Resolved(binding, credential, true));
    }

    private void noModel() {
        when(modelResolver.resolve(USER, ROLE))
                .thenReturn(new CompanionModelResolver.Resolved(null, null, true));
    }

    @Test
    void firstLineOpensTheEveningAndTheMarkerNeverReachesTheText() {
        modelConfigured();
        role.setGreeting("（她抬起头。）你来了。");
        when(modelService.generateReply(any(), any(), any(), any())).thenReturn(
                CompanionModelService.ModelReply.success(
                        "<state>{\"mood\":\"warm\",\"motion\":\"lean_in\",\"doing\":\"把杯子往前推了推\",\"gesture\":\"rest\"}</state>\n坐吧。"));

        CompanionChatResponse response = service.sendMessage(USER, ROLE, "晚上好", "rain-fireplace", "c-1");

        assertEquals(900L, response.getSessionId());
        assertEquals("rain-fireplace", response.getSceneKey());
        assertEquals("（她抬起头。）你来了。", response.getOpeningMessage().getContent());
        assertEquals("坐吧。", response.getRoleMessage().getContent());
        Map<String, String> expectedState = Map.of(
                "mood", "warm", "motion", "lean_in", "doing", "把杯子往前推了推", "gesture", "rest");
        assertEquals(expectedState, response.getRoleMessage().getState());
        assertFalse(response.getFallback());
        assertEquals("warm", response.getStatus().getMood());
        assertEquals("lean_in", response.getStatus().getMotion());
        assertEquals("rest", response.getStatus().getGesture());
        assertEquals("把杯子往前推了推", response.getStatus().getSummary());

        // opening, user line, reply — all filed under the new evening, in that order.
        assertEquals(3, saved.size());
        assertTrue(saved.stream().allMatch(m -> Long.valueOf(900L).equals(m.getSessionId())));
        assertEquals(CompanionMessage.SenderType.user, saved.get(1).getSenderType());
        assertEquals("c-1", saved.get(1).getClientMsgId());
        assertEquals(expectedState, CompanionStateTag.fromJson(saved.get(2).getStateJson()).toMap());
        ArgumentCaptor<CompanionStatus> status = ArgumentCaptor.forClass(CompanionStatus.class);
        verify(statusRepository).save(status.capture());
        assertEquals("rest", status.getValue().getGesture());
        when(statusRepository.findByUserIdAndRoleId(USER, ROLE)).thenReturn(Optional.of(status.getValue()));
        assertEquals("rest", service.getStatus(USER, ROLE).getGesture());
        when(messageRepository.findByConversationIdOrderByIdAsc(50L)).thenReturn(saved);
        List<CompanionMessageDTO> history = service.getConversation(USER, ROLE);
        assertEquals(expectedState, history.get(2).getState());
        assertNull(history.get(0).getState());
        assertNull(history.get(1).getState());
        verify(growthRepository).save(any(CompanionGrowth.class));
    }

    @Test
    void aRetryOfAnAnsweredSendReturnsTheStoredExchange() {
        modelConfigured();
        CompanionMessage user = new CompanionMessage();
        user.setId(11L);
        user.setRoleId(ROLE);
        user.setSenderType(CompanionMessage.SenderType.user);
        user.setContent("晚上好");
        user.setSessionId(900L);
        CompanionMessage reply = new CompanionMessage();
        reply.setId(12L);
        reply.setRoleId(ROLE);
        reply.setSenderType(CompanionMessage.SenderType.role);
        reply.setContent("坐吧。");
        reply.setFallback(false);
        reply.setSessionId(900L);
        when(messageRepository.findFirstByUserIdAndRoleIdAndClientMsgIdOrderByIdAsc(USER, ROLE, "c-1"))
                .thenReturn(Optional.of(user));
        when(messageRepository.findFirstByConversationIdAndIdGreaterThanOrderByIdAsc(50L, 11L))
                .thenReturn(Optional.of(reply));

        CompanionChatResponse response = service.sendMessage(USER, ROLE, "晚上好", null, "c-1");

        assertEquals(11L, response.getUserMessage().getId());
        assertEquals(12L, response.getRoleMessage().getId());
        assertTrue(saved.isEmpty(), "a retry must not file anything");
        verify(modelService, never()).generateReply(any(), any(), any(), any());
        verify(growthRepository, never()).save(any());
    }

    @Test
    void aRetryOfAnUnansweredSendReusesTheUserLine() {
        modelConfigured();
        CompanionMessage user = new CompanionMessage();
        user.setId(11L);
        user.setRoleId(ROLE);
        user.setSenderType(CompanionMessage.SenderType.user);
        user.setContent("晚上好");
        user.setSessionId(900L);
        TavernSession active = new TavernSession();
        active.setId(900L);
        active.setRoleId(ROLE);
        active.setStatus(TavernSession.Status.active);
        when(sessionRepository.findFirstByUserIdAndRoleIdAndStatusOrderByStartedAtDesc(
                USER, ROLE, TavernSession.Status.active)).thenReturn(Optional.of(active));
        when(messageRepository.findFirstByUserIdAndRoleIdAndClientMsgIdOrderByIdAsc(USER, ROLE, "c-1"))
                .thenReturn(Optional.of(user));
        when(messageRepository.findFirstByConversationIdAndIdGreaterThanOrderByIdAsc(50L, 11L))
                .thenReturn(Optional.empty());
        when(modelService.generateReply(any(), any(), any(), any()))
                .thenReturn(CompanionModelService.ModelReply.success("坐吧。"));

        CompanionChatResponse response = service.sendMessage(USER, ROLE, "晚上好", null, "c-1");

        assertEquals(11L, response.getUserMessage().getId());
        assertEquals(1, saved.size(), "only the reply is new");
        assertEquals(CompanionMessage.SenderType.role, saved.get(0).getSenderType());
        assertNull(response.getRoleMessage().getState(), "no marker, no state");
    }

    @Test
    void aCannedReplyDoesNotMoveTheBond() {
        noModel();
        CompanionChatResponse response = service.sendMessage(USER, ROLE, "晚上好", null, "c-2");

        assertTrue(response.getFallback());
        assertTrue(response.getMemoryIds().isEmpty());
        assertNull(response.getStatus().getMood());
        verify(growthRepository, never()).save(any());
        verify(modelService, never()).generateReply(any(), any(), any(), any());
    }

    @Test
    void aConfiguredStateOnlyReplyLeavesTheUserLineUnanswered() {
        modelConfigured();
        when(modelService.generateReply(any(), any(), any(), any())).thenReturn(
                CompanionModelService.ModelReply.success("<state>{\"mood\":\"warm\"}</state>\n"));

        var result = service.sendMessage(USER, ROLE, "你好", null, "retry-this");

        assertNull(result.getRoleMessage());
        assertFalse(result.getFallback());
        assertEquals("retry-this", result.getUserMessage().getClientMsgId());
        assertEquals(1, saved.size());
        verify(growthRepository, never()).save(any());
        verify(statusRepository, never()).save(any());
        verify(credentialRepository, never()).save(any());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void configuredRateLimitKeepsTheUnansweredLineAndTypedRetryInformation(boolean streaming) {
        modelConfigured();
        var error = rateLimit();
        var failure = new CompanionModelService.ModelReply(null, false, error);
        if (streaming) {
            when(modelService.streamReply(any(), any(), any(), any(), any())).thenReturn(failure);
        } else {
            when(modelService.generateReply(any(), any(), any(), any())).thenReturn(failure);
        }
        var result = streaming
                ? service.sendMessageStreaming(USER, ROLE, "你好", null, "retry-rate", piece -> fail("invented delta"), null)
                : service.sendMessage(USER, ROLE, "你好", null, "retry-rate");
        assertEquals(error, result.getModelError());
        assertFalse(result.getFallback());
        assertFalse(result.getPartial());
        assertNull(result.getRoleMessage());
        assertEquals("retry-rate", result.getUserMessage().getClientMsgId());
        assertEquals(1, saved.size());
        verify(credentialRepository, never()).save(any());
        verify(statusRepository, never()).save(any());
        verify(growthRepository, never()).save(any());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void configuredFailureWithVisibleOutputIsPartialNotASuccessfulExchange(boolean streaming) {
        modelConfigured();
        String raw = "<state>{\"mood\":\"shy\"}</state>\n半句";
        var error = rateLimit();
        var failure = new CompanionModelService.ModelReply(raw, true, error);
        if (streaming) {
            when(modelService.streamReply(any(), any(), any(), any(), any())).thenAnswer(inv -> {
                inv.<Consumer<String>>getArgument(4).accept(raw);
                return failure;
            });
        } else {
            when(modelService.generateReply(any(), any(), any(), any())).thenReturn(failure);
        }
        var seen = new StringBuilder();
        var result = streaming
                ? service.sendMessageStreaming(USER, ROLE, "你好", null, "retry-partial", seen::append, null)
                : service.sendMessage(USER, ROLE, "你好", null, "retry-partial");
        if (streaming) assertEquals("半句", seen.toString());
        assertEquals("半句", result.getRoleMessage().getContent());
        assertEquals(error, result.getModelError());
        assertTrue(result.getPartial());
        assertFalse(result.getFallback());
        assertEquals("retry-partial", result.getUserMessage().getClientMsgId());
        assertEquals(2, saved.size());
        verify(growthRepository, never()).save(any());
        verify(statusRepository, never()).save(any());
        verify(credentialRepository, never()).save(any());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void aRetryOfACutShortReplyAsksTheModelAgainAndReplacesTheFragment(boolean streaming) {
        modelConfigured();
        // The repository answers from what was actually filed, so the retry sees the first attempt.
        when(messageRepository.findFirstByUserIdAndRoleIdAndClientMsgIdOrderByIdAsc(USER, ROLE, "c-9"))
                .thenAnswer(inv -> saved.stream().filter(m -> "c-9".equals(m.getClientMsgId())).findFirst());
        when(messageRepository.findFirstByConversationIdAndIdGreaterThanOrderByIdAsc(eq(50L), anyLong()))
                .thenAnswer(inv -> saved.stream().filter(m -> m.getId() > inv.<Long>getArgument(1)).findFirst());
        when(messageRepository.findBySessionIdOrderByIdDesc(eq(900L), any())).thenAnswer(inv -> {
            List<CompanionMessage> newestFirst = new ArrayList<>(saved);
            java.util.Collections.reverse(newestFirst);
            return newestFirst;
        });
        doAnswer(inv -> saved.remove(inv.<CompanionMessage>getArgument(0)))
                .when(messageRepository).delete(any(CompanionMessage.class));

        String fragment = "<state>{\"mood\":\"shy\"}</state>\n半句";
        var failure = new CompanionModelService.ModelReply(fragment, true, rateLimit());
        var success = CompanionModelService.ModelReply.success("完整的一句。");
        List<List<String>> shownToModel = new ArrayList<>();
        org.mockito.stubbing.Answer<CompanionModelService.ModelReply> model = inv -> {
            shownToModel.add(inv.<List<CompanionMessage>>getArgument(1).stream().map(CompanionMessage::getContent).toList());
            boolean first = shownToModel.size() == 1;
            if (streaming) inv.<Consumer<String>>getArgument(4).accept(first ? fragment : success.content());
            return first ? failure : success;
        };
        when(modelService.streamReply(any(), any(), any(), any(), any())).thenAnswer(model);
        when(modelService.generateReply(any(), any(), any(), any())).thenAnswer(model);

        CompanionChatResponse cutShort = streaming
                ? service.sendMessageStreaming(USER, ROLE, "你好", null, "c-9", piece -> {}, null)
                : service.sendMessage(USER, ROLE, "你好", null, "c-9");

        assertTrue(cutShort.getPartial());
        assertEquals("半句", cutShort.getRoleMessage().getContent());
        assertEquals(Boolean.TRUE, cutShort.getRoleMessage().getPartial(), "a reload must still see the reply as cut short");
        assertEquals(Map.of("mood", "shy"), cutShort.getRoleMessage().getState(), "the marker is not a state field");
        assertNull(cutShort.getUserMessage().getPartial());
        Long fragmentId = cutShort.getRoleMessage().getId();
        verify(growthRepository, never()).save(any());

        TavernSession active = new TavernSession();
        active.setId(900L);
        active.setRoleId(ROLE);
        active.setStatus(TavernSession.Status.active);
        when(sessionRepository.findFirstByUserIdAndRoleIdAndStatusOrderByStartedAtDesc(
                USER, ROLE, TavernSession.Status.active)).thenReturn(Optional.of(active));
        StringBuilder seen = new StringBuilder();

        CompanionChatResponse retry = streaming
                ? service.sendMessageStreaming(USER, ROLE, "你好", null, "c-9", seen::append, null)
                : service.sendMessage(USER, ROLE, "你好", null, "c-9");

        assertEquals(2, shownToModel.size(), "the retry must reach the model, not replay the fragment");
        assertEquals(List.of("你好"), shownToModel.get(1), "the fragment is gone before the model is asked again");
        if (streaming) assertEquals("完整的一句。", seen.toString());
        assertEquals("完整的一句。", retry.getRoleMessage().getContent());
        assertNotEquals(fragmentId, retry.getRoleMessage().getId());
        assertNull(retry.getRoleMessage().getPartial());
        assertFalse(retry.getPartial());
        assertNull(retry.getModelError());
        assertEquals(cutShort.getUserMessage().getId(), retry.getUserMessage().getId());
        assertEquals(List.of("你好", "完整的一句。"), saved.stream().map(CompanionMessage::getContent).toList(),
                "one user line, one reply: no duplicate and no leftover fragment");
        verify(growthRepository, times(1)).save(any());

        // A third send under the same id is now an ordinary replay of the finished reply.
        CompanionChatResponse replay = service.sendMessage(USER, ROLE, "你好", null, "c-9");
        assertEquals(retry.getRoleMessage().getId(), replay.getRoleMessage().getId());
        assertEquals(2, shownToModel.size());
        assertEquals(2, saved.size());
    }

    private static JsonNode rateLimit() {
        return JsonNodeFactory.instance.objectNode().put("code", "RATE_LIMIT").put("httpStatus", 429)
                .put("summary", "wait").put("upstreamCode", "slow_down").put("retryAfterSeconds", 20);
    }

    @Test
    void missingExplicitCredentialDoesNotGenerateAnOfflineLine() {
        var binding = new CompanionModelBinding();
        binding.setUserId(USER);
        binding.setRoleId(ROLE);
        binding.setModelName("role-model");
        when(modelResolver.resolve(USER, ROLE)).thenReturn(new CompanionModelResolver.Resolved(binding, null, false));
        var result = service.sendMessage(USER, ROLE, "你好", null, "keep-id");
        assertNull(result.getRoleMessage());
        assertFalse(result.getFallback());
        assertEquals("keep-id", result.getUserMessage().getClientMsgId());
        verifyNoInteractions(modelService);
        verify(growthRepository, never()).save(any());
        verify(statusRepository, never()).save(any());
    }

    static Stream<Arguments> reportedStates() {
        return Stream.of(
                Arguments.of("{\"gesture\":\"wave\"}", "calm", "listen", "wave"),
                Arguments.of("{\"gesture\":\"rest\"}", "calm", "listen", "rest"),
                Arguments.of("{\"mood\":\"warm\"}", "warm", "listen", null),
                Arguments.of("{\"motion\":\"nod\"}", "calm", "nod", null),
                Arguments.of("{\"doing\":\"擦杯子\"}", "calm", "listen", null),
                Arguments.of("{\"mood\":null,\"motion\":null,\"gesture\":null}", "calm", "listen", null),
                Arguments.of("{\"mood\":\"unknown\",\"motion\":\"backflip\",\"gesture\":\"wave\"}", "calm", "listen", "wave"),
                Arguments.of("{\"mood\":\"warm\",\"gesture\":\"dance\"}", "warm", "listen", null),
                Arguments.of("{\"mood\":\"unknown\",\"motion\":\"backflip\",\"gesture\":\"dance\"}", "calm", "listen", null),
                Arguments.of("{}", "calm", "listen", null),
                Arguments.of(null, "calm", "listen", null));
    }

    @ParameterizedTest
    @MethodSource("reportedStates")
    void partialStateRetainsKnownMoodAndMotionButNeverAnOldGesture(
            String body, String expectedMood, String expectedMotion, String expectedGesture) {
        modelConfigured();
        CompanionStatus existing = new CompanionStatus();
        existing.setUserId(USER);
        existing.setRoleId(ROLE);
        existing.setMood("calm");
        existing.setMotion("listen");
        existing.setGesture("sip");
        existing.setSummary("听你说话");
        existing.setSummaryAuthored(true);
        when(statusRepository.findByUserIdAndRoleId(USER, ROLE)).thenReturn(Optional.of(existing));
        String marker = body == null ? "" : "<state>" + body + "</state>\n";
        when(modelService.generateReply(any(), any(), any(), any()))
                .thenReturn(CompanionModelService.ModelReply.success(marker + "嗯。"));

        CompanionStatusDTO status = service.sendMessage(USER, ROLE, "你好", null, "state-update").getStatus();

        verify(statusRepository).save(existing);
        assertEquals(expectedMood, existing.getMood());
        assertEquals(expectedMotion, existing.getMotion());
        assertEquals(expectedGesture, existing.getGesture());
        assertEquals(expectedMood, status.getMood());
        assertEquals(expectedMotion, status.getMotion());
        assertEquals(expectedGesture, status.getGesture());
        assertEquals(status, service.getStatus(USER, ROLE));
    }

    @Test
    void onlyTonightAndChosenMemoriesReachTheModel() {
        modelConfigured();
        CompanionMemory pinned = new CompanionMemory();
        pinned.setId(1L);
        pinned.setContent("对方怕冷");
        pinned.setConfirmed(true);
        CompanionMemory loose = new CompanionMemory();
        loose.setId(2L);
        loose.setContent("上次聊到了雪原");
        loose.setConfirmed(false);
        when(memoryRepository.findByUserIdAndRoleIdOrderByCreatedAtDesc(USER, ROLE)).thenReturn(List.of(loose, pinned));
        CompanionGrowth growth = new CompanionGrowth();
        growth.setIntimacy(60);
        growth.setTrust(75);
        growth.setCoGrowth(1);
        when(growthRepository.findByUserIdAndRoleId(USER, ROLE)).thenReturn(Optional.of(growth));
        when(modelService.generateReply(any(), any(), any(), any()))
                .thenReturn(CompanionModelService.ModelReply.success("坐吧。"));

        CompanionChatResponse response = service.sendMessage(USER, ROLE, "晚上好", "empty-bar", "c-3");

        ArgumentCaptor<CompanionPromptBuilder.Context> ctx = ArgumentCaptor.forClass(CompanionPromptBuilder.Context.class);
        verify(modelService).generateReply(ctx.capture(), any(), any(), any());
        assertEquals(List.of("对方怕冷", "上次聊到了雪原"), ctx.getValue().memories(), "confirmed first");
        assertEquals(60, ctx.getValue().intimacy());
        assertEquals("empty-bar", ctx.getValue().sceneKey());
        assertTrue(ctx.getValue().withState());
        assertEquals(List.of(1L, 2L), response.getMemoryIds());
        // History is read by evening, never by the whole relationship.
        verify(messageRepository).findBySessionIdOrderByIdDesc(eq(900L), any());
        verify(messageRepository, never()).findByConversationIdOrderByCreatedAtDesc(anyLong(), any());
    }

    @Test
    void streamingDeliversTextWithoutTheMarkerAndStateAsAnEvent() {
        modelConfigured();
        String raw = "<state>{\"mood\":\"shy\",\"motion\":\"look_away\",\"gesture\":\"cover\"}</state>\n（她别开视线。）嗯。";
        when(modelService.streamReply(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Consumer<String> onDelta = inv.getArgument(4);
            for (int i = 0; i < raw.length(); i += 3) onDelta.accept(raw.substring(i, Math.min(raw.length(), i + 3)));
            return CompanionModelService.ModelReply.success(raw);
        });

        StringBuilder text = new StringBuilder();
        List<String> events = new ArrayList<>();
        Object[] stateAt = new Object[2];
        CompanionChatResponse response = service.sendMessageStreaming(USER, ROLE, "你脸红了", null, "c-4",
                text::append,
                (event, payload) -> {
                    events.add(event);
                    if ("state".equals(event)) {
                        stateAt[0] = payload;
                        stateAt[1] = text.length();
                    }
                });

        assertEquals("（她别开视线。）嗯。", text.toString());
        assertEquals("（她别开视线。）嗯。", response.getRoleMessage().getContent());
        assertEquals(List.of("context", "state"), events);
        Map<String, String> expectedState = Map.of("mood", "shy", "motion", "look_away", "gesture", "cover");
        assertEquals(expectedState, stateAt[0]);
        assertEquals(expectedState, response.getRoleMessage().getState());
        assertEquals(expectedState, CompanionStateTag.fromJson(saved.get(saved.size() - 1).getStateJson()).toMap());
        assertEquals("cover", response.getStatus().getGesture());
        ArgumentCaptor<CompanionStatus> status = ArgumentCaptor.forClass(CompanionStatus.class);
        verify(statusRepository).save(status.capture());
        assertEquals("cover", status.getValue().getGesture());
        assertEquals(0, stateAt[1], "the state arrives before the first word");
        assertFalse(Boolean.TRUE.equals(response.getPartial()));
    }

    @Test
    void aReaderWhoLeavesStopsTheModelAndKeepsWhatWasWritten() {
        modelConfigured();
        when(modelService.streamReply(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Consumer<String> onDelta = inv.getArgument(4);
            onDelta.accept("第一句。");
            onDelta.accept("第二句。");
            return fail("the second piece must abort the stream");
        });

        int[] calls = {0};
        CompanionChatResponse response = service.sendMessageStreaming(USER, ROLE, "说说看", null, "c-5",
                piece -> {
                    if (++calls[0] == 2) throw new IllegalStateException("client gone");
                },
                (event, payload) -> {});

        assertTrue(response.getPartial());
        assertEquals("第一句。", response.getRoleMessage().getContent());
        assertFalse(response.getFallback(), "a stopped reply is hers, not a canned line");
        assertNull(response.getModelError(), "a reader leaving is not a provider failure");
        verify(growthRepository, never()).save(any());
        verify(statusRepository, never()).save(any());
    }

    @Test
    void abortBeforeVisibleTextLeavesTheOriginalLineUnanswered() {
        modelConfigured();
        when(modelService.streamReply(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            inv.<Consumer<String>>getArgument(4).accept("<state>{\"mood\":\"warm\"}</state>你好");
            return fail("reader should have aborted");
        });
        var result = service.sendMessageStreaming(USER, ROLE, "hello", null, "abort-id", piece -> {
            throw new IllegalStateException("client gone");
        }, null);
        assertTrue(result.getPartial());
        assertNull(result.getModelError());
        assertNull(result.getRoleMessage());
        assertFalse(result.getFallback());
        assertEquals("abort-id", result.getUserMessage().getClientMsgId());
        assertEquals(1, saved.size());
        verify(statusRepository, never()).save(any());
        verify(growthRepository, never()).save(any());
    }
}
