package com.nexus.chat.repository;
import com.nexus.chat.model.CompanionSkillVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface CompanionSkillVersionRepository extends JpaRepository<CompanionSkillVersion,Long> {
 List<CompanionSkillVersion> findBySkillIdAndUserIdOrderByRevisionDesc(Long skillId,Long userId);
 @org.springframework.data.jpa.repository.Query("select coalesce(sum(length(v.packageJson)),0) from CompanionSkillVersion v where v.userId=:owner")
 long storedCharacters(Long owner);
 Optional<CompanionSkillVersion> findBySkillIdAndUserIdAndRevision(Long skillId,Long userId,int revision);
}
