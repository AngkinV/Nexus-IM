package com.nexus.chat.repository;

import com.nexus.chat.model.MessageEditHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageEditHistoryRepository extends JpaRepository<MessageEditHistory, Long> {

    List<MessageEditHistory> findByMessageIdOrderByEditedAtAsc(Long messageId);

    long countByMessageId(Long messageId);
}
