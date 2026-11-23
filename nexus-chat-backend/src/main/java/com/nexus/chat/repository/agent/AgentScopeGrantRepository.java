package com.nexus.chat.repository.agent;
import com.nexus.chat.model.agent.AgentScopeGrant;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface AgentScopeGrantRepository extends JpaRepository<AgentScopeGrant,String> {
List<AgentScopeGrant> findBySessionIdAndRunId(String sessionId,String runId);
 List<AgentScopeGrant> findBySessionId(String sessionId);
}
