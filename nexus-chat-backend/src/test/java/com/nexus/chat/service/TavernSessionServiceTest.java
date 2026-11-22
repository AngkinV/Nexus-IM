package com.nexus.chat.service;

import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Unit tests with mocked repositories, model and transaction manager; no database rollback is exercised. */
class TavernSessionServiceTest {
    private static final Long USER = 7L;
    private static final Long OTHER_USER = 8L;
    private static final Long ROLE = 41L;
    private static final Long SESSION = 900L;
    private static final Long PREVIOUS_SESSION = 800L;
    private static final Long CONVERSATION = 50L;
    private static final String SCENE = "rain-fireplace";
    private static final CompanionModelService.ClosingReply CLOSING =
            new CompanionModelService.ClosingReply("下次见。", "聊起了雨夜。");

    private final TavernSessionRepository sessions = mock(TavernSessionRepository.class);
    private final CompanionRoleRepository roles = mock(CompanionRoleRepository.class);
    private final CompanionMessageRepository messages = mock(CompanionMessageRepository.class);
    private final CompanionMemoryRepository memories = mock(CompanionMemoryRepository.class);
    private final CompanionModelResolver resolver = mock(CompanionModelResolver.class);
    private final CompanionModelService model = mock(CompanionModelService.class);
    private final CompanionFallbackService fallback = mock(CompanionFallbackService.class);
    private final CompanionConversationRepository conversations = mock(CompanionConversationRepository.class);
    private final CompanionStatusRepository statuses = mock(CompanionStatusRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
    private final CompanionConversationGuard guard = new CompanionConversationGuard();
    private final CompanionModelAssetCatalog modelAssets = mock(CompanionModelAssetCatalog.class);
    private TavernSessionService service;
    private CompanionRole role;
    private TavernSession session;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transaction);
        service = new TavernSessionService(sessions, roles, messages, memories, resolver, model,
                fallback, conversations, statuses, transactionManager, guard, modelAssets);
        role = new CompanionRole();
        role.setId(ROLE);
        role.setUserId(USER);
        role.setKind(CompanionRole.Kind.guest);
        role.setOriginSessionId(PREVIOUS_SESSION);
        session = new TavernSession();
        session.setId(SESSION);
        session.setUserId(USER);
        session.setRoleId(ROLE);
        session.setSceneKey(SCENE);
        session.setStartedAt(LocalDateTime.of(2026, 11, 20, 19, 0));
    }

    @ParameterizedTest
    @EnumSource(TavernSession.Status.class)
    void adoptAcceptsAnOwnedSessionForTheSameRole(TavernSession.Status status) {
        ownRole();
        session.setStatus(status);
        when(sessions.findByIdAndUserId(SESSION, USER)).thenReturn(Optional.of(session));

        service.adoptRole(USER, ROLE, SESSION);

        assertEquals(CompanionRole.Kind.regular, role.getKind());
        assertEquals(SESSION, role.getOriginSessionId());
        var order = inOrder(roles, sessions);
        order.verify(roles).findByIdAndUserId(ROLE, USER);
        order.verify(sessions).findByIdAndUserId(SESSION, USER);
        order.verify(roles).save(role);
        verifyNoMoreInteractions(roles, sessions);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = 800L)
    void adoptWithoutSessionPreservesTheExistingOrigin(Long origin) {
        ownRole();
        role.setOriginSessionId(origin);

        service.adoptRole(USER, ROLE, null);

        assertEquals(CompanionRole.Kind.regular, role.getKind());
        assertEquals(origin, role.getOriginSessionId());
        verify(roles).save(role);
        verifyNoInteractions(sessions);
    }

    @Test
    void adoptRejectsAnotherUsersOrMissingSessionWithoutChangingOrSavingRole() {
        ownRole();
        // The user-scoped repository lookup hides both foreign and nonexistent sessions.
        when(sessions.findByIdAndUserId(SESSION, USER)).thenReturn(Optional.empty());

        var failure = assertThrows(ResponseStatusException.class, () -> service.adoptRole(USER, ROLE, SESSION));

        assertEquals(HttpStatus.NOT_FOUND, failure.getStatusCode());
        assertRoleUnchangedAndUnsaved();
        verify(sessions).findByIdAndUserId(SESSION, USER);
        verifyNoMoreInteractions(sessions);
    }

    @Test
    void adoptRejectsAnotherRolesSessionWithoutChangingOrSavingRole() {
        ownRole();
        session.setRoleId(ROLE + 1);
        when(sessions.findByIdAndUserId(SESSION, USER)).thenReturn(Optional.of(session));

        var failure = assertThrows(ResponseStatusException.class, () -> service.adoptRole(USER, ROLE, SESSION));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        assertRoleUnchangedAndUnsaved();
        verify(sessions).findByIdAndUserId(SESSION, USER);
        verifyNoMoreInteractions(sessions);
    }

    @Test
    void repeatedCloseDoesNotGenerateOrSaveAnotherFarewellOrMemory() {
        var history = ownSessionWithHistory();
        var resolved = configuredModel();
        when(model.generateClosing(role, history, resolved.binding(), resolved.credential(), SCENE)).thenReturn(CLOSING);

        var first = service.closeSession(USER, SESSION);
        var second = service.closeSession(USER, SESSION);

        assertEquals("closed", first.getStatus());
        assertEquals(first, second);
        assertNotNull(first.getClosedAt());
        verify(model).generateClosing(role, history, resolved.binding(), resolved.credential(), SCENE);
        verify(messages).findBySessionIdOrderByIdDesc(SESSION, PageRequest.of(0, 30));
        verify(sessions).save(session);
        verify(messages).save(any(CompanionMessage.class));
        verify(memories).save(any(CompanionMemory.class));
        verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        verify(transactionManager).commit(transaction);
        verifyNoMoreInteractions(transactionManager);
        assertGuardReleased();
    }

    @Test
    void closeUsesOnlyThisSessionsChronologicalHistoryBeforeStartingTheMockTransaction() {
        var history = ownSessionWithHistory();
        var resolved = configuredModel();
        when(model.generateClosing(role, history, resolved.binding(), resolved.credential(), SCENE)).thenAnswer(inv -> {
            verifyNoInteractions(transactionManager);
            return CLOSING;
        });

        var result = service.closeSession(USER, SESSION);

        assertEquals("closed", result.getStatus());
        assertEquals(CLOSING.closingLine(), result.getClosingLine());
        assertEquals(CLOSING.summary(), result.getSummary());
        var last = ArgumentCaptor.forClass(CompanionMessage.class);
        var memory = ArgumentCaptor.forClass(CompanionMemory.class);
        verify(messages).findBySessionIdOrderByIdDesc(SESSION, PageRequest.of(0, 30));
        var order = inOrder(model, transactionManager, sessions, messages, memories);
        order.verify(model).generateClosing(role, history, resolved.binding(), resolved.credential(), SCENE);
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(sessions).findByIdAndUserId(SESSION, USER);
        order.verify(sessions).save(session);
        order.verify(messages).save(last.capture());
        order.verify(memories).save(memory.capture());
        order.verify(messages).countBySessionId(SESSION);
        order.verify(transactionManager).commit(transaction);
        // No whole-conversation history lookup or other session can be added unnoticed.
        verifyNoMoreInteractions(messages, memories, transactionManager);
        verifyNoInteractions(fallback, conversations, statuses);
        assertEquals(USER, last.getValue().getUserId());
        assertEquals(ROLE, last.getValue().getRoleId());
        assertEquals(SESSION, last.getValue().getSessionId());
        assertEquals(CONVERSATION, last.getValue().getConversationId());
        assertEquals(CompanionMessage.SenderType.role, last.getValue().getSenderType());
        assertEquals(CLOSING.closingLine(), last.getValue().getContent());
        assertFalse(last.getValue().getFallback());
        assertEquals(USER, memory.getValue().getUserId());
        assertEquals(ROLE, memory.getValue().getRoleId());
        assertEquals(CLOSING.summary(), memory.getValue().getContent());
        assertEquals(CompanionMemory.MemoryType.long_term, memory.getValue().getMemoryType());
        assertFalse(memory.getValue().getConfirmed());
        assertFalse(memory.getValue().getShared());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fallbackClosesWithoutInventingAMemory(boolean modelConfigured) {
        var history = ownSessionWithHistory();
        if (modelConfigured) {
            var resolved = configuredModel();
            when(model.generateClosing(role, history, resolved.binding(), resolved.credential(), SCENE)).thenReturn(null);
        } else {
            when(resolver.resolve(USER, ROLE)).thenReturn(new CompanionModelResolver.Resolved(null, null, true));
        }
        when(fallback.generateFarewell(role)).thenReturn("路上小心。");

        var result = service.closeSession(USER, SESSION);

        assertEquals("closed", result.getStatus());
        assertNotNull(result.getClosedAt());
        assertEquals("路上小心。", result.getClosingLine());
        assertNull(result.getSummary());
        assertNull(session.getSummary());
        var last = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messages).save(last.capture());
        assertEquals(SESSION, last.getValue().getSessionId());
        assertEquals(CONVERSATION, last.getValue().getConversationId());
        assertEquals("路上小心。", last.getValue().getContent());
        assertTrue(last.getValue().getFallback());
        verify(sessions).save(session);
        verify(fallback).generateFarewell(role);
        verifyNoInteractions(memories);
        if (modelConfigured) {
            verify(model).generateClosing(any(), any(), any(), any(), any());
        } else {
            verifyNoInteractions(model);
        }
        verify(transactionManager).commit(transaction);
    }

    @Test
    void emptySessionClosesWithoutCallingModelOrSavingFarewellOrMemory() {
        ownRole();
        when(sessions.findByIdAndUserId(SESSION, USER)).thenReturn(Optional.of(session));
        when(messages.findBySessionIdOrderByIdDesc(SESSION, PageRequest.of(0, 30))).thenReturn(List.of());

        var result = service.closeSession(USER, SESSION);

        assertEquals("closed", result.getStatus());
        assertNotNull(result.getClosedAt());
        assertNull(result.getClosingLine());
        assertNull(result.getSummary());
        verify(sessions).save(session);
        verify(messages, never()).save(any());
        verifyNoInteractions(resolver, model, fallback, memories);
        verify(transactionManager).commit(transaction);
        assertGuardReleased();
    }

    @Test
    void closeHoldsGuardDuringModelAndWritesThenReleasesIt() throws Exception {
        var history = ownSessionWithHistory();
        var resolved = configuredModel();
        var modelEntered = new CountDownLatch(1);
        var finishModel = new CountDownLatch(1);
        when(model.generateClosing(role, history, resolved.binding(), resolved.credential(), SCENE)).thenAnswer(inv -> {
            modelEntered.countDown();
            assertTrue(finishModel.await(5, TimeUnit.SECONDS), "the test must release the mocked model");
            return CLOSING;
        });
        when(sessions.save(session)).thenAnswer(inv -> {
            assertEquals(HttpStatus.CONFLICT,
                    assertThrows(ResponseStatusException.class, () -> guard.acquire(USER, ROLE)).getStatusCode());
            return session;
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var closing = executor.submit(() -> service.closeSession(USER, SESSION));
            assertTrue(modelEntered.await(5, TimeUnit.SECONDS), "close must reach the mocked model");
            assertEquals(HttpStatus.CONFLICT,
                    assertThrows(ResponseStatusException.class, () -> service.closeSession(USER, SESSION)).getStatusCode());
            assertEquals(HttpStatus.CONFLICT,
                    assertThrows(ResponseStatusException.class, () -> service.openSession(USER, ROLE, SCENE)).getStatusCode());
            verifyNoInteractions(transactionManager);
            verify(sessions, never()).save(any());
            verify(messages, never()).save(any());

            finishModel.countDown();
            assertEquals("closed", closing.get(5, TimeUnit.SECONDS).getStatus());
            assertGuardReleased();
            verify(model).generateClosing(role, history, resolved.binding(), resolved.credential(), SCENE);
            verify(sessions).save(session);
            verify(messages).save(any(CompanionMessage.class));
            verify(memories).save(any(CompanionMemory.class));
        } finally {
            finishModel.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "write"})
    void closeReleasesGuardAfterModelOrWriteFailure(String failingStep) {
        var history = ownSessionWithHistory();
        var resolved = configuredModel();
        var failure = new IllegalStateException("test failure");
        if ("model".equals(failingStep)) {
            when(model.generateClosing(role, history, resolved.binding(), resolved.credential(), SCENE)).thenThrow(failure);
        } else {
            when(model.generateClosing(role, history, resolved.binding(), resolved.credential(), SCENE)).thenReturn(CLOSING);
            when(sessions.save(session)).thenThrow(failure);
        }

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.closeSession(USER, SESSION)));

        assertGuardReleased();
        verify(messages, never()).save(any());
        verifyNoInteractions(memories);
        if ("model".equals(failingStep)) {
            verifyNoInteractions(transactionManager);
        } else {
            verify(transactionManager).getTransaction(any(TransactionDefinition.class));
            verify(transactionManager).rollback(transaction);
            verify(transactionManager, never()).commit(any());
        }
    }

    @Test
    void inviteAlsoCallsModelBeforeStartingTheMockWriteTransaction() {
        var resolved = usableModel();
        when(resolver.resolveForUser(USER)).thenReturn(resolved);
        var generated = new CompanionModelService.GeneratedRole(
                "旅人", "沉默、谨慎", "简短", "望着雨幕", "从山间而来", "晚上好。");
        when(model.generateRole(resolved.binding(), resolved.credential(), SCENE)).thenAnswer(inv -> {
            verifyNoInteractions(transactionManager);
            return generated;
        });
        when(roles.save(any(CompanionRole.class))).thenAnswer(inv -> {
            CompanionRole saved = inv.getArgument(0);
            saved.setId(ROLE);
            return saved;
        });

        var invited = service.inviteRole(USER, SCENE);

        assertEquals(USER, invited.getUserId());
        assertEquals(CompanionRole.Kind.guest, invited.getKind());
        assertEquals(generated.name(), invited.getName());
        var presence = ArgumentCaptor.forClass(CompanionStatus.class);
        var order = inOrder(model, transactionManager, roles, statuses);
        order.verify(model).generateRole(resolved.binding(), resolved.credential(), SCENE);
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(roles).save(invited);
        order.verify(statuses).save(presence.capture());
        order.verify(transactionManager).commit(transaction);
        verifyNoMoreInteractions(transactionManager);
        assertEquals(ROLE, presence.getValue().getRoleId());
        assertEquals(generated.presence(), presence.getValue().getSummary());
        assertTrue(presence.getValue().getSummaryAuthored());
    }

    @Test
    void inviteWithNoModelAvailableStillSeatsATextOnlyGuest() {
        offlinePersona(USER);
        savingAssignsAnId();
        // The mocked catalogue picks nothing: no stage-ready model is uploaded.

        var invited = service.inviteRole(USER, SCENE, null);

        assertNull(invited.getModelUrl());
        assertNull(invited.getModelType());
        assertEquals("无名的旅人", invited.getName());
        verify(modelAssets, never()).requireStageAsset(any());
        verify(roles).save(invited);
        verifyNoInteractions(model, statuses);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aBlankModelChoiceMeansLetTheTavernPick(String blank) {
        offlinePersona(USER);
        savingAssignsAnId();
        when(modelAssets.pickForGuest(any())).thenReturn(asset("b.vrm"));

        var invited = service.inviteRole(USER, SCENE, blank);

        assertEquals("/models/b.vrm", invited.getModelUrl());
        assertEquals("vrm", invited.getModelType());
        verify(modelAssets, never()).requireStageAsset(any());
    }

    @Test
    void aChosenModelIsBoundInItsCanonicalFormWithoutAskingForAnotherOne() {
        offlinePersona(USER);
        savingAssignsAnId();
        // The client sent a bare name; what is stored is the catalogue's URL.
        when(modelAssets.requireStageAsset("a.vrm")).thenReturn(asset("a.vrm"));

        var invited = service.inviteRole(USER, SCENE, "a.vrm");

        assertEquals("/models/a.vrm", invited.getModelUrl());
        assertEquals("vrm", invited.getModelType());
        verify(modelAssets, never()).pickForGuest(any());
        verify(roles, never()).findByUserIdOrderByIdAsc(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            CompanionModelAssetCatalog.MODEL_URL_INVALID,
            CompanionModelAssetCatalog.MODEL_TYPE_UNSUPPORTED,
            CompanionModelAssetCatalog.MODEL_NOT_FOUND,
            CompanionModelAssetCatalog.MODEL_FILE_INVALID})
    void aChosenModelThatCannotBeWornFailsBeforeTheModelCallAndIsNeverReplaced(String code) {
        var refusal = new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
        when(modelAssets.requireStageAsset("/models/chosen.vrm")).thenThrow(refusal);

        assertSame(refusal, assertThrows(ResponseStatusException.class,
                () -> service.inviteRole(USER, SCENE, "/models/chosen.vrm")));

        // No substitute model, no model call paid for, nothing written.
        verify(modelAssets, never()).pickForGuest(any());
        verifyNoInteractions(resolver, model, transactionManager, roles, statuses);
        assertInviteGuardReleased();
    }

    @Test
    void anUnreadableCatalogueFailsAnAutomaticInviteBeforeTheModelCall() {
        var unavailable = new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, CompanionModelAssetCatalog.CATALOG_UNAVAILABLE);
        when(modelAssets.pickForGuest(any())).thenThrow(unavailable);

        assertSame(unavailable, assertThrows(ResponseStatusException.class,
                () -> service.inviteRole(USER, SCENE, null)));

        verifyNoInteractions(resolver, model, transactionManager, statuses);
        verify(roles, never()).save(any());
        assertInviteGuardReleased();
    }

    @Test
    void wornModelsAreCountedByCanonicalUrlAndOnlyForVisibleCharacters() {
        offlinePersona(USER);
        savingAssignsAnId();
        // The real path rules decide identity; only the pick itself is stubbed.
        when(modelAssets.canonicalUrl(any())).thenAnswer(inv ->
                new CompanionModelAssetCatalog().canonicalUrl(inv.getArgument(0)));
        when(roles.findByUserIdOrderByIdAsc(USER)).thenReturn(List.of(
                wearing("a.vrm", false),                     // bare name, as older rows stored it
                wearing("/models/a.vrm", false),             // the same file again
                wearing("  /models/a.vrm ", false),
                wearing("/models/hidden.vrm", true),         // conversation deleted: off the seat list
                wearing("https://cdn.example/remote.vrm", false),
                wearing("/models/../outside.vrm", false),
                wearing("  ", false),
                wearing(null, false)));
        when(modelAssets.pickForGuest(any())).thenReturn(asset("b.vrm"));

        service.inviteRole(USER, SCENE, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> inUse = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(modelAssets).pickForGuest(inUse.capture());
        assertEquals(java.util.Set.of("/models/a.vrm"), inUse.getValue());
    }

    @Test
    void aSecondInviteForTheSameAccountIsRefusedWhileTheFirstIsStillRunning() throws Exception {
        var resolved = usableModel();
        when(resolver.resolveForUser(USER)).thenReturn(resolved);
        offlinePersona(OTHER_USER);
        savingAssignsAnId();
        var generated = new CompanionModelService.GeneratedRole(
                "旅人", "沉默、谨慎", "简短", "望着雨幕", "从山间而来", "晚上好。");
        var modelEntered = new CountDownLatch(1);
        var finishModel = new CountDownLatch(1);
        when(model.generateRole(resolved.binding(), resolved.credential(), SCENE)).thenAnswer(inv -> {
            modelEntered.countDown();
            assertTrue(finishModel.await(5, TimeUnit.SECONDS), "the test must release the mocked model");
            return generated;
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> service.inviteRole(USER, SCENE, null));
            assertTrue(modelEntered.await(5, TimeUnit.SECONDS), "the first invite must reach the mocked model");

            var busy = assertThrows(ResponseStatusException.class, () -> service.inviteRole(USER, SCENE, null));
            assertEquals(HttpStatus.CONFLICT, busy.getStatusCode());
            assertEquals(TavernSessionService.INVITE_IN_PROGRESS, busy.getReason());
            // The refused invite did nothing: one catalogue pick, one model call, no write yet.
            verify(modelAssets, times(1)).pickForGuest(any());
            verify(model, times(1)).generateRole(any(), any(), any());
            verify(roles, never()).save(any());
            // The guard is held across the model call without a transaction open.
            verifyNoInteractions(transactionManager);

            // Another account is not held up by this one.
            assertEquals(OTHER_USER, service.inviteRole(OTHER_USER, SCENE, null).getUserId());

            finishModel.countDown();
            assertEquals(generated.name(), first.get(5, TimeUnit.SECONDS).getName());
            assertInviteGuardReleased();
        } finally {
            finishModel.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"resolver", "model", "write"})
    void inviteReleasesItsGuardAfterAFailure(String failingStep) {
        var failure = new IllegalStateException("test failure");
        var resolved = usableModel();
        if ("resolver".equals(failingStep)) {
            when(resolver.resolveForUser(USER)).thenThrow(failure);
        } else {
            when(resolver.resolveForUser(USER)).thenReturn(resolved);
        }
        if ("model".equals(failingStep)) {
            when(model.generateRole(resolved.binding(), resolved.credential(), SCENE)).thenThrow(failure);
        }
        when(roles.save(any(CompanionRole.class))).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.inviteRole(USER, SCENE, null)));

        if ("write".equals(failingStep)) {
            verify(transactionManager).rollback(transaction);
            verify(transactionManager, never()).commit(any());
        } else {
            verifyNoInteractions(transactionManager);
        }
        assertInviteGuardReleased();
    }

    private void ownRole() {
        when(roles.findByIdAndUserId(ROLE, USER)).thenReturn(Optional.of(role));
    }

    /** No usable channel for this account: the invite uses the built-in persona, no model call. */
    private void offlinePersona(Long userId) {
        when(resolver.resolveForUser(userId)).thenReturn(new CompanionModelResolver.Resolved(null, null, true));
    }

    private void savingAssignsAnId() {
        when(roles.save(any(CompanionRole.class))).thenAnswer(inv -> {
            CompanionRole saved = inv.getArgument(0);
            saved.setId(ROLE);
            return saved;
        });
    }

    private CompanionModelAssetCatalog.ModelAsset asset(String fileName) {
        return new CompanionModelAssetCatalog.ModelAsset(fileName, "/models/" + fileName, 24L, "vrm", true);
    }

    private CompanionRole wearing(String modelUrl, boolean conversationHidden) {
        var worn = new CompanionRole();
        worn.setUserId(USER);
        worn.setModelUrl(modelUrl);
        worn.setConversationHidden(conversationHidden);
        return worn;
    }

    /** A fresh invite goes through, so the previous one let go of the account. */
    private void assertInviteGuardReleased() {
        reset(resolver, roles, modelAssets);
        offlinePersona(USER);
        savingAssignsAnId();
        assertEquals(USER, service.inviteRole(USER, SCENE, "").getUserId());
    }

    private List<CompanionMessage> ownSessionWithHistory() {
        ownRole();
        when(sessions.findByIdAndUserId(SESSION, USER)).thenReturn(Optional.of(session));
        var first = message(101L, CompanionMessage.SenderType.user, "下雨了。");
        var second = message(102L, CompanionMessage.SenderType.role, "坐吧。");
        when(messages.findBySessionIdOrderByIdDesc(SESSION, PageRequest.of(0, 30)))
                .thenReturn(List.of(second, first));
        when(messages.countBySessionId(SESSION)).thenReturn(3);
        return List.of(first, second);
    }

    private CompanionMessage message(Long id, CompanionMessage.SenderType sender, String content) {
        var message = new CompanionMessage();
        message.setId(id);
        message.setUserId(USER);
        message.setRoleId(ROLE);
        message.setConversationId(CONVERSATION);
        message.setSessionId(SESSION);
        message.setSenderType(sender);
        message.setContent(content);
        return message;
    }

    private CompanionModelResolver.Resolved configuredModel() {
        var resolved = usableModel();
        when(resolver.resolve(USER, ROLE)).thenReturn(resolved);
        return resolved;
    }

    private CompanionModelResolver.Resolved usableModel() {
        var binding = new CompanionModelBinding();
        binding.setEndpoint("https://example.invalid/v1");
        binding.setModelName("test-model");
        var credential = new ModelCredential();
        credential.setApiKeyEncrypted("test-only");
        return new CompanionModelResolver.Resolved(binding, credential, true);
    }

    private void assertRoleUnchangedAndUnsaved() {
        assertEquals(CompanionRole.Kind.guest, role.getKind());
        assertEquals(PREVIOUS_SESSION, role.getOriginSessionId());
        verify(roles).findByIdAndUserId(ROLE, USER);
        verify(roles, never()).save(any());
        verifyNoMoreInteractions(roles);
    }

    private void assertGuardReleased() {
        try (var lease = guard.acquire(USER, ROLE)) {
            assertNotNull(lease);
        }
    }
}
