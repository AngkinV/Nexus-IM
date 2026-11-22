package com.nexus.chat.repository;

import com.nexus.chat.model.CompanionMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompanionMessageRepository extends JpaRepository<CompanionMessage, Long> {
    List<CompanionMessage> findByConversationIdOrderByCreatedAtAsc(Long conversationId);
    List<CompanionMessage> findTop20ByConversationIdOrderByCreatedAtDesc(Long conversationId);
    List<CompanionMessage> findByConversationIdOrderByCreatedAtDesc(Long conversationId, Pageable pageable);
    List<CompanionMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);
    Integer countBySessionId(Long sessionId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from CompanionMessage m where m.userId = :userId and m.roleId = :roleId")
    int deleteHistory(Long userId, Long roleId);

    // created_at has one-second resolution; two rows written in the same second
    // (an opening line and the message that prompted it) only order reliably by id.
    List<CompanionMessage> findByConversationIdOrderByIdAsc(Long conversationId);
    List<CompanionMessage> findBySessionIdOrderByIdDesc(Long sessionId, Pageable pageable);

    /** A send the client is retrying: same character, same client id. */
    java.util.Optional<CompanionMessage> findFirstByUserIdAndRoleIdAndClientMsgIdOrderByIdAsc(
            Long userId, Long roleId, String clientMsgId);

    /**
     * The newest message of every character this user has, one query for the
     * whole seat list. The list shows that line as the row's preview, the way
     * the chat list does — a second query per character would make opening the
     * tavern cost N round trips again.
     */
    @org.springframework.data.jpa.repository.Query(
            "select m from CompanionMessage m where m.id in "
                    + "(select max(m2.id) from CompanionMessage m2 where m2.userId = :userId group by m2.roleId)")
    List<CompanionMessage> findLatestPerRole(Long userId);

    /**
     * The newest message of one character — the single-row counterpart of
     * {@link #findLatestPerRole}, using the same "highest id" notion of newest.
     */
    java.util.Optional<CompanionMessage> findFirstByUserIdAndRoleIdOrderByIdDesc(Long userId, Long roleId);

    /** Whatever was written right after a given message — its reply, if it got one. */
    java.util.Optional<CompanionMessage> findFirstByConversationIdAndIdGreaterThanOrderByIdAsc(
            Long conversationId, Long id);
}
