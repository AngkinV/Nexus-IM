package com.nexus.chat.agent;

import com.nexus.chat.service.agent.scope.ScopeDtos.*;
import com.nexus.chat.service.agent.scope.AgentScopeService;
import java.util.*;

/** Serialization-only scope fixture; authority tests use real AgentScopeService separately. */
public final class AgentScopeWireFixture {
    private AgentScopeWireFixture() {}
    public static AuthorizedScope scope(Long actor,String session,String run) {
        var range=TimeRange.unbounded();
        return new AuthorizedScope(1,"fixture_grant",actor,session,run,1,1,1,List.of(),List.of(),range,
            AgentScopeService.key(actor,session,1,List.of(),List.of(),range));
    }
    public static void bind(com.nexus.chat.dto.agent.AgentSessionAndChatDtos.SessionChatRequest request,Long actor,String session) {
        if(request.getRunId()==null) request.setRunId("run_fixture_001");
        request.setAuthorizedScope(scope(actor,session,request.getRunId()));
    }
}
