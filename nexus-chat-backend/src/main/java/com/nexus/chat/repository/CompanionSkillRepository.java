package com.nexus.chat.repository;
import com.nexus.chat.model.CompanionSkill;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface CompanionSkillRepository extends JpaRepository<CompanionSkill,Long> {
 List<CompanionSkill> findByUserIdAndDeletedFalseOrderByUpdatedAtDesc(Long userId);
 Optional<CompanionSkill> findByIdAndUserId(Long id,Long userId);
 @Query(value="select id from users where id=:owner for update",nativeQuery=true)
 Optional<Long> lockOwnerBudget(@Param("owner")Long owner);
 long countByUserIdAndDeletedFalse(Long userId);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from CompanionSkill s where s.id=:id and s.userId=:owner")
 Optional<CompanionSkill> lockOwned(@Param("id")Long id,@Param("owner")Long owner);
}
