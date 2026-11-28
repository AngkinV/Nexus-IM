package com.nexus.chat.repository;
import com.nexus.chat.model.CompanionSkillBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface CompanionSkillBindingRepository extends JpaRepository<CompanionSkillBinding,Long> {
 List<CompanionSkillBinding> findByUserIdAndRoleIdOrderByIdAsc(Long userId,Long roleId);
 List<CompanionSkillBinding> findByUserIdAndSkillId(Long userId,Long skillId);
 Optional<CompanionSkillBinding> findByUserIdAndRoleIdAndSkillId(Long userId,Long roleId,Long skillId);
}
