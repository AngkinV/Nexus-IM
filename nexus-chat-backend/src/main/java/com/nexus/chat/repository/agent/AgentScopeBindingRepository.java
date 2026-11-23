package com.nexus.chat.repository.agent;
import com.nexus.chat.model.agent.AgentScopeBinding;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface AgentScopeBindingRepository extends JpaRepository<AgentScopeBinding,String> {
@Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
@Query("select b from AgentScopeBinding b where b.sessionId = :sessionId")
Optional<AgentScopeBinding> lockBySessionId(@org.springframework.data.repository.query.Param("sessionId") String sessionId);
}
