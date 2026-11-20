package com.nexus.chat.service;

import com.nexus.chat.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CompanionConversationHistoryService {
    private final CompanionRoleRepository roles;
    private final CompanionMessageRepository messages;
    private final TavernSessionRepository sessions;
    private final CompanionConversationRepository conversations;
    private final CompanionStatusRepository statuses;
    private final CompanionConversationGuard guard;
    private final TransactionTemplate tx;

    public CompanionConversationHistoryService(CompanionRoleRepository roles,
                                               CompanionMessageRepository messages,
                                               TavernSessionRepository sessions,
                                               CompanionConversationRepository conversations,
                                               CompanionStatusRepository statuses,
                                               CompanionConversationGuard guard,
                                               PlatformTransactionManager transactionManager) {
        this.roles = roles;
        this.messages = messages;
        this.sessions = sessions;
        this.conversations = conversations;
        this.statuses = statuses;
        this.guard = guard;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public void restore(Long userId, Long roleId) {
        try (var ignored = guard.acquire(userId, roleId)) {
            tx.executeWithoutResult(transaction -> {
                roles.findByIdAndUserId(roleId, userId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "role not found"));
                roles.showConversation(userId, roleId);
            });
        }
    }

    public record ClearedHistory(int deletedMessages, int deletedSessions) {}

    public ClearedHistory clear(Long userId, Long roleId) {
        try (var ignored = guard.acquire(userId, roleId)) {
            return tx.execute(transaction -> {
                roles.findByIdAndUserId(roleId, userId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "role not found"));
                int deletedMessages = messages.deleteHistory(userId, roleId);
                roles.hideConversation(userId, roleId);
                int deletedSessions = sessions.deleteHistory(userId, roleId);
                conversations.clearLastMessageAt(userId, roleId);
                statuses.deleteHistory(userId, roleId);
                return new ClearedHistory(deletedMessages, deletedSessions);
            });
        }
    }
}
