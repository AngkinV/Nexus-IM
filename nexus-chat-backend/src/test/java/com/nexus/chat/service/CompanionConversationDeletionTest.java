package com.nexus.chat.service;

import com.nexus.chat.model.CompanionRole;
import com.nexus.chat.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CompanionConversationDeletionTest {
    private static final Long USER = 7L;
    private static final Long ROLE = 41L;
    private final CompanionRoleRepository roles = mock(CompanionRoleRepository.class);
    private final CompanionMessageRepository messages = mock(CompanionMessageRepository.class);
    private final TavernSessionRepository sessions = mock(TavernSessionRepository.class);
    private final CompanionConversationRepository conversations = mock(CompanionConversationRepository.class);
    private final CompanionStatusRepository statuses = mock(CompanionStatusRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
    private final CompanionConversationGuard guard = new CompanionConversationGuard();
    private CompanionConversationHistoryService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transaction);
        service = new CompanionConversationHistoryService(
                roles, messages, sessions, conversations, statuses, guard, transactionManager);
    }

    @Test
    void clearCommitsOnlyScopedHistoryWritesAndReportsMessageAndSessionCounts() {
        ownRole();
        when(messages.deleteHistory(USER, ROLE)).thenReturn(7);
        when(sessions.deleteHistory(USER, ROLE)).thenReturn(2);

        var result = service.clear(USER, ROLE);

        assertEquals(7, result.deletedMessages());
        assertEquals(2, result.deletedSessions());
        var order = inOrder(transactionManager, roles, messages, sessions, conversations, statuses);
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(roles).findByIdAndUserId(ROLE, USER);
        order.verify(messages).deleteHistory(USER, ROLE);
        order.verify(roles).hideConversation(USER, ROLE);
        order.verify(sessions).deleteHistory(USER, ROLE);
        order.verify(conversations).clearLastMessageAt(USER, ROLE);
        order.verify(statuses).deleteHistory(USER, ROLE);
        order.verify(transactionManager).commit(transaction);
        verifyNoMoreInteractions(transactionManager, roles, messages, sessions, conversations, statuses);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoreCommitsOnlyVisibilityWriteUnderGuardWithoutCreatingHistory(boolean hidden) {
        ownRole().setConversationHidden(hidden);
        when(roles.showConversation(USER, ROLE)).thenReturn(1);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation -> {
            assertEquals(HttpStatus.CONFLICT,
                    assertThrows(ResponseStatusException.class, () -> guard.acquire(USER, ROLE)).getStatusCode());
            return transaction;
        });
        doAnswer(invocation -> {
            assertEquals(HttpStatus.CONFLICT,
                    assertThrows(ResponseStatusException.class, () -> guard.acquire(USER, ROLE)).getStatusCode());
            return null;
        }).when(transactionManager).commit(transaction);

        service.restore(USER, ROLE);

        var order = inOrder(transactionManager, roles);
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(roles).findByIdAndUserId(ROLE, USER);
        order.verify(roles).showConversation(USER, ROLE);
        order.verify(transactionManager).commit(transaction);
        verifyNoMoreInteractions(transactionManager, roles);
        verifyNoInteractions(messages, sessions, conversations, statuses);
        try (var ignored = guard.acquire(USER, ROLE)) {
            assertNotNull(ignored);
        }
    }

    @Test
    void restoreWriteFailureRollsBackWithoutOtherWritesAndReleasesGuard() {
        ownRole();
        var failure = new IllegalStateException("visibility write failed");
        when(roles.showConversation(USER, ROLE)).thenThrow(failure);
        doAnswer(invocation -> {
            assertEquals(HttpStatus.CONFLICT,
                    assertThrows(ResponseStatusException.class, () -> guard.acquire(USER, ROLE)).getStatusCode());
            return null;
        }).when(transactionManager).rollback(transaction);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.restore(USER, ROLE)));

        var order = inOrder(transactionManager, roles);
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(roles).findByIdAndUserId(ROLE, USER);
        order.verify(roles).showConversation(USER, ROLE);
        order.verify(transactionManager).rollback(transaction);
        verify(transactionManager, never()).commit(any());
        verifyNoMoreInteractions(transactionManager, roles);
        verifyNoInteractions(messages, sessions, conversations, statuses);
        try (var ignored = guard.acquire(USER, ROLE)) {
            assertNotNull(ignored);
        }
    }

    @Test
    void historyServiceHasNoIndependentMemoryOrGrowthDependencies() {
        Set<Class<?>> dependencies = Arrays.stream(CompanionConversationHistoryService.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getType).collect(Collectors.toSet());

        assertEquals(Set.of(CompanionRoleRepository.class, CompanionMessageRepository.class,
                TavernSessionRepository.class, CompanionConversationRepository.class,
                CompanionStatusRepository.class, CompanionConversationGuard.class, TransactionTemplate.class),
                dependencies);
    }

    @Test
    void missingOwnedRoleReturns404WithoutDeletingAndRollsBack() {
        when(roles.findByIdAndUserId(ROLE, USER)).thenReturn(Optional.empty());

        var failure = assertThrows(ResponseStatusException.class, () -> service.clear(USER, ROLE));

        assertEquals(HttpStatus.NOT_FOUND, failure.getStatusCode());
        verify(roles).findByIdAndUserId(ROLE, USER);
        verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        verify(transactionManager).rollback(transaction);
        verifyNoMoreInteractions(roles, transactionManager);
        verifyNoInteractions(messages, sessions, conversations, statuses);
        try (var ignored = guard.acquire(USER, ROLE)) {
            assertNotNull(ignored);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"messages", "visibility", "sessions", "conversation", "status"})
    void clearWriteFailureRollsBackRatherThanCommitsAndReleasesGuard(String failingWrite) {
        ownRole();
        var failure = new IllegalStateException("write failed");
        switch (failingWrite) {
            case "messages" -> when(messages.deleteHistory(USER, ROLE)).thenThrow(failure);
            case "visibility" -> when(roles.hideConversation(USER, ROLE)).thenThrow(failure);
            case "sessions" -> when(sessions.deleteHistory(USER, ROLE)).thenThrow(failure);
            case "conversation" -> when(conversations.clearLastMessageAt(USER, ROLE)).thenThrow(failure);
            case "status" -> when(statuses.deleteHistory(USER, ROLE)).thenThrow(failure);
            default -> fail("unknown write: " + failingWrite);
        }

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.clear(USER, ROLE)));

        verify(roles).findByIdAndUserId(ROLE, USER);
        verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        verify(transactionManager).rollback(transaction);
        verify(transactionManager, never()).commit(any());
        verifyNoMoreInteractions(transactionManager);
        try (var ignored = guard.acquire(USER, ROLE)) {
            assertNotNull(ignored);
        }
    }

    @Test
    void repeatedClearCommitsAgainAndReturnsZeroCounts() {
        ownRole();
        var nextTransaction = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transaction, nextTransaction);
        when(messages.deleteHistory(USER, ROLE)).thenReturn(7, 0);
        when(sessions.deleteHistory(USER, ROLE)).thenReturn(2, 0);

        assertEquals(new CompanionConversationHistoryService.ClearedHistory(7, 2), service.clear(USER, ROLE));
        assertEquals(new CompanionConversationHistoryService.ClearedHistory(0, 0), service.clear(USER, ROLE));

        verify(roles, times(2)).findByIdAndUserId(ROLE, USER);
        verify(messages, times(2)).deleteHistory(USER, ROLE);
        verify(roles, times(2)).hideConversation(USER, ROLE);
        verify(sessions, times(2)).deleteHistory(USER, ROLE);
        verify(conversations, times(2)).clearLastMessageAt(USER, ROLE);
        verify(statuses, times(2)).deleteHistory(USER, ROLE);
        verify(transactionManager, times(2)).getTransaction(any(TransactionDefinition.class));
        verify(transactionManager).commit(transaction);
        verify(transactionManager).commit(nextTransaction);
        verifyNoMoreInteractions(transactionManager, roles, messages, sessions, conversations, statuses);
    }

    @Test
    void samePairConflictsAcrossThreadsAndCanRetryAfterRelease() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            try (var ignored = guard.acquire(USER, ROLE)) {
                var competing = executor.submit(() -> assertThrows(ResponseStatusException.class, () -> {
                    try (var unexpected = guard.acquire(USER, ROLE)) {
                        fail("a competing thread acquired the same conversation");
                    }
                }));
                assertEquals(HttpStatus.CONFLICT, competing.get(5, TimeUnit.SECONDS).getStatusCode());
            }
            assertTrue(executor.submit(() -> {
                try (var ignored = guard.acquire(USER, ROLE)) {
                    return true;
                }
            }).get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void differentRoleAndDifferentUserDoNotBlockOrReleaseTheBusyPair() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var ignored = guard.acquire(USER, ROLE)) {
            assertTrue(executor.submit(() -> {
                try (var otherRole = guard.acquire(USER, ROLE + 1);
                     var otherUser = guard.acquire(USER + 1, ROLE)) {
                    return true;
                }
            }).get(5, TimeUnit.SECONDS));
            assertEquals(HttpStatus.CONFLICT,
                    assertThrows(ResponseStatusException.class, () -> guard.acquire(USER, ROLE)).getStatusCode());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private CompanionRole ownRole() {
        var role = new CompanionRole();
        role.setId(ROLE);
        role.setUserId(USER);
        when(roles.findByIdAndUserId(ROLE, USER)).thenReturn(Optional.of(role));
        return role;
    }
}
