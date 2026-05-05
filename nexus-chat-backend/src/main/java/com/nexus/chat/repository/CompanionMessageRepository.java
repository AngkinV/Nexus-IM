package com.nexus.chat.repository;

import com.nexus.chat.model.CompanionMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompanionMessageRepository extends JpaRepository<CompanionMessage, Long> {
    List<CompanionMessage> findByConversationIdOrderByCreatedAtAsc(Long conversationId);
    List<CompanionMessage> findTop20ByConversationIdOrderByCreatedAtDesc(Long conversationId);
}
