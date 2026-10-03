package com.nexus.chat.repository;

import com.nexus.chat.model.BlockedUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BlockedUserRepository extends JpaRepository<BlockedUser, Long> {

    List<BlockedUser> findByUserIdOrderByCreatedAtDesc(Long userId);

    boolean existsByUserIdAndBlockedUserId(Long userId, Long blockedUserId);

    void deleteByUserIdAndBlockedUserId(Long userId, Long blockedUserId);

}
