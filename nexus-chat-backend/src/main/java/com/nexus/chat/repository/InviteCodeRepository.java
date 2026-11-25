package com.nexus.chat.repository;

import com.nexus.chat.model.InviteCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {

    String USABLE = "i.codeHash = :codeHash AND i.revoked = false AND i.usedCount < i.maxUses"
            + " AND (i.expiresAt IS NULL OR i.expiresAt > :now)";

    @Query("SELECT COUNT(i) > 0 FROM InviteCode i WHERE " + USABLE)
    boolean isUsable(@Param("codeHash") String codeHash, @Param("now") LocalDateTime now);

    /**
     * Takes one use in a single conditional UPDATE, so the database decides between concurrent
     * registrations: 1 means this caller got a use, 0 means there was none to take.
     */
    @Modifying
    @Query("UPDATE InviteCode i SET i.usedCount = i.usedCount + 1 WHERE " + USABLE)
    int consume(@Param("codeHash") String codeHash, @Param("now") LocalDateTime now);
}
