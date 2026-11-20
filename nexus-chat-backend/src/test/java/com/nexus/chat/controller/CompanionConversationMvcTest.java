package com.nexus.chat.controller;

import com.nexus.chat.dto.CompanionChatResponse;
import com.nexus.chat.exception.GlobalExceptionHandler;
import com.nexus.chat.model.CompanionRole;
import com.nexus.chat.repository.*;
import com.nexus.chat.service.CompanionConversationGuard;
import com.nexus.chat.service.CompanionConversationHistoryService;
import com.nexus.chat.service.CompanionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CompanionConversationMvcTest {
    private static final Long USER = 7L;
    private static final Long ROLE = 41L;
    private static final String HISTORY_URL = "/api/companion/conversations/" + ROLE;
    private final CompanionRoleRepository roles = mock(CompanionRoleRepository.class);
    private final CompanionMessageRepository messages = mock(CompanionMessageRepository.class);
    private final TavernSessionRepository sessions = mock(TavernSessionRepository.class);
    private final CompanionConversationRepository conversations = mock(CompanionConversationRepository.class);
    private final CompanionStatusRepository statuses = mock(CompanionStatusRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final CompanionService companionService = mock(CompanionService.class);
    private final CompanionConversationGuard guard = new CompanionConversationGuard();
    private CompanionConversationHistoryService history;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        history = spy(new CompanionConversationHistoryService(
                roles, messages, sessions, conversations, statuses, guard, transactionManager));
        mvc = MockMvcBuilders.standaloneSetup(new CompanionConversationController(history),
                        new CompanionController(companionService, guard))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unauthenticatedHistoryMutationIs401EvenWithClaimedUser(boolean restoring) throws Exception {
        var request = restoring ? post(HISTORY_URL) : delete(HISTORY_URL);
        mvc.perform(request.param("userId", USER.toString()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));

        verifyNoInteractions(history, roles, messages, sessions, conversations, statuses,
                transactionManager, companionService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void forgedUserIs403BeforeHistoryServiceIsCalled(boolean restoring) throws Exception {
        login();
        var request = restoring ? post(HISTORY_URL) : delete(HISTORY_URL);
        mvc.perform(request.param("userId", "999"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(history, roles, messages, sessions, conversations, statuses,
                transactionManager, companionService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void roleOutsideAuthenticatedUserScopeIs404WithoutWrites(boolean restoring) throws Exception {
        login();
        when(roles.findByIdAndUserId(ROLE, USER)).thenReturn(Optional.empty());

        mvc.perform(restoring ? post(HISTORY_URL) : delete(HISTORY_URL))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));

        if (restoring) {
            verify(history).restore(USER, ROLE);
        } else {
            verify(history).clear(USER, ROLE);
        }
        verify(roles).findByIdAndUserId(ROLE, USER);
        verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        verify(transactionManager).rollback(any(SimpleTransactionStatus.class));
        verifyNoMoreInteractions(history, roles, transactionManager);
        verifyNoInteractions(messages, sessions, conversations, statuses, companionService);
        try (var ignored = guard.acquire(USER, ROLE)) {
            assertNotNull(ignored);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulDeleteUsesTokenUserWithOrWithoutMatchingClaim(boolean includeClaim) throws Exception {
        login();
        ownedHistory();
        var request = delete(HISTORY_URL);
        if (includeClaim) request.param("userId", USER.toString());

        mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedMessages").value(7))
                .andExpect(jsonPath("$.deletedSessions").value(2));

        verify(history).clear(USER, ROLE);
        verifySuccessfulClear();
        verifyNoMoreInteractions(history);
        verifyNoInteractions(companionService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulRestoreUsesTokenUserAndReturns204WithoutCreatingHistory(boolean includeClaim) throws Exception {
        login();
        ownedRole();
        when(roles.showConversation(USER, ROLE)).thenReturn(1);
        var request = post(HISTORY_URL);
        if (includeClaim) request.param("userId", USER.toString());

        mvc.perform(request).andExpect(status().isNoContent()).andExpect(content().string(""));

        verify(history).restore(USER, ROLE);
        var order = inOrder(transactionManager, roles);
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(roles).findByIdAndUserId(ROLE, USER);
        order.verify(roles).showConversation(USER, ROLE);
        order.verify(transactionManager).commit(any(SimpleTransactionStatus.class));
        verifyNoMoreInteractions(history, roles, transactionManager);
        verifyNoInteractions(messages, sessions, conversations, statuses, companionService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deleteAndRestoreAre409DuringSendOrStreamThenDeleteSucceedsAfterGeneration(boolean streaming) throws Exception {
        login();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Answer<CompanionChatResponse> blocking = invocation -> {
            if (streaming) invocation.<Consumer<String>>getArgument(5).accept("partial reply");
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS), "generation was not released");
            return new CompanionChatResponse();
        };
        if (streaming) {
            when(companionService.sendMessageStreaming(eq(USER), eq(ROLE), eq("hello"),
                    isNull(), isNull(), any(), any())).thenAnswer(blocking);
        } else {
            when(companionService.sendMessage(USER, ROLE, "hello", null, null)).thenAnswer(blocking);
        }
        var executor = Executors.newSingleThreadExecutor();
        try {
            var generating = executor.submit(() -> {
                login();
                try {
                    return mvc.perform(post(streaming ? "/api/companion/messages/stream" : "/api/companion/messages")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"roleId\":" + ROLE + ",\"content\":\"hello\"}"))
                            .andExpect(status().isOk()).andReturn();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS), "generation did not start");
            assertFalse(generating.isDone());

            mvc.perform(delete(HISTORY_URL)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value("conversation is busy"));
            mvc.perform(post(HISTORY_URL)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value("conversation is busy"));

            verify(history).clear(USER, ROLE);
            verify(history).restore(USER, ROLE);
            verifyNoInteractions(roles, messages, sessions, conversations, statuses, transactionManager);
            assertFalse(generating.isDone());
            release.countDown();
            var completed = generating.get(5, TimeUnit.SECONDS);
            if (streaming) {
                assertTrue(completed.getResponse().getContentAsString().contains("event: delta"));
                assertTrue(completed.getResponse().getContentAsString().contains("event: done"));
            }
            ownedHistory();
            mvc.perform(delete(HISTORY_URL)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.deletedMessages").value(7))
                    .andExpect(jsonPath("$.deletedSessions").value(2));

            verify(history, times(2)).clear(USER, ROLE);
            verifySuccessfulClear();
            if (streaming) {
                verify(companionService).sendMessageStreaming(eq(USER), eq(ROLE), eq("hello"),
                        isNull(), isNull(), any(), any());
            } else {
                verify(companionService).sendMessage(USER, ROLE, "hello", null, null);
            }
            verifyNoMoreInteractions(history, companionService);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void ownedRole() {
        var role = new CompanionRole();
        role.setId(ROLE);
        role.setUserId(USER);
        when(roles.findByIdAndUserId(ROLE, USER)).thenReturn(Optional.of(role));
    }

    private void ownedHistory() {
        ownedRole();
        when(messages.deleteHistory(USER, ROLE)).thenReturn(7);
        when(sessions.deleteHistory(USER, ROLE)).thenReturn(2);
    }

    private void verifySuccessfulClear() {
        verify(roles).findByIdAndUserId(ROLE, USER);
        verify(messages).deleteHistory(USER, ROLE);
        verify(roles).hideConversation(USER, ROLE);
        verify(sessions).deleteHistory(USER, ROLE);
        verify(conversations).clearLastMessageAt(USER, ROLE);
        verify(statuses).deleteHistory(USER, ROLE);
        verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        verify(transactionManager).commit(any(SimpleTransactionStatus.class));
        verifyNoMoreInteractions(roles, messages, sessions, conversations, statuses, transactionManager);
    }

    private void login() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER, null, List.of()));
    }
}
