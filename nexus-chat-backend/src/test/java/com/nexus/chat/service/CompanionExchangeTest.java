package com.nexus.chat.service;

import com.nexus.chat.dto.CompanionChatResponse;
import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import com.nexus.chat.util.ModelEndpointGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    @Mock ModelEndpointGuard endpointGuard;
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
                sessionRepository, endpointGuard, modelResolver);

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
        binding.setEndpoint("https://example.invalid/v1");
        binding.setModelName("test-model");
        ModelCredential credential = new ModelCredential();
        credential.setApiKeyEncrypted("x");
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
                        "<state>{\"mood\":\"warm\",\"motion\":\"lean_in\",\"doing\":\"把杯子往前推了推\"}</state>\n坐吧。"));

        CompanionChatResponse response = service.sendMessage(USER, ROLE, "晚上好", "rain-fireplace", "c-1");

        assertEquals(900L, response.getSessionId());
        assertEquals("rain-fireplace", response.getSceneKey());
        assertEquals("（她抬起头。）你来了。", response.getOpeningMessage().getContent());
        assertEquals("坐吧。", response.getRoleMessage().getContent());
        assertEquals(Map.of("mood", "warm", "motion", "lean_in", "doing", "把杯子往前推了推"),
                response.getRoleMessage().getState());
        assertFalse(response.getFallback());
        assertEquals("warm", response.getStatus().getMood());
        assertEquals("把杯子往前推了推", response.getStatus().getSummary());

        // opening, user line, reply — all filed under the new evening, in that order.
        assertEquals(3, saved.size());
        assertTrue(saved.stream().allMatch(m -> Long.valueOf(900L).equals(m.getSessionId())));
        assertEquals(CompanionMessage.SenderType.user, saved.get(1).getSenderType());
        assertEquals("c-1", saved.get(1).getClientMsgId());
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
        String raw = "<state>{\"mood\":\"shy\",\"motion\":\"look_away\"}</state>\n（她别开视线。）嗯。";
        when(modelService.streamReply(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Consumer<String> onDelta = inv.getArgument(4);
            for (int i = 0; i < raw.length(); i += 3) onDelta.accept(raw.substring(i, Math.min(raw.length(), i + 3)));
            return raw;
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
        assertEquals(Map.of("mood", "shy", "motion", "look_away"), stateAt[0]);
        assertEquals(0, stateAt[1], "the state arrives before the first word");
        assertFalse(Boolean.TRUE.equals(response.getPartial()));
    }

    @Test
    void aReaderWhoLeavesStopsTheModelAndKeepsWhatWasWritten() {
        modelConfigured();
        when(modelService.streamReply(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Consumer<String> onDelta = inv.getArgument(4);
            try {
                onDelta.accept("第一句。");
                onDelta.accept("第二句。");
                fail("the second piece must abort the stream");
            } catch (CompanionModelService.ReaderGone expected) {
                // what the real client does: drop the provider connection
            }
            return "第一句。第二句。";
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
    }
}
