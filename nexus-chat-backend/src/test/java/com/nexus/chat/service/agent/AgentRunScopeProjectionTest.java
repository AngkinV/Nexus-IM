package com.nexus.chat.service.agent;

import com.nexus.chat.dto.agent.AgentSessionAndChatDtos;
import com.nexus.chat.service.agent.scope.ScopeDtos;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Source-only regressions; no runtime verification has been performed. */
class AgentRunScopeProjectionTest {
    @Test void revokedScopeDoesNotBlockSafeTerminalAndRoundTripsOwnerRetryMetadata() throws Exception {
        var fixture=new AgentScopeRegistryRegressionTest();
        // Test store owner is 7; use an empty owned binding to avoid incidental membership.
        var selection=new ScopeDtos.Selection(List.of(),ScopeDtos.TimeRange.unbounded());
        fixture.scopes.create(7L,"as_test",selection);
        var scope=fixture.scopes.authorize(7L,"as_test","run_scope001",selection,true,1L,null,false);
        var store=new AgentWorkbenchTestStore();var service=store.runService();
        ReflectionTestUtils.setField(service,"scopes",fixture.scopes);
        var request=new AgentSessionAndChatDtos.SessionChatRequest();request.setInput("Original question");
        request.setScopeSelection(selection);request.setAuthorizedScope(scope);request.setExpectedBindingVersion(1L);
        var begun=service.begin(7L,"as_test",scope.runId(),request);
        assertNotNull(begun.get("scopeSelection"));
        // The payload round-trips through JSON, so compare numerically, not by boxed type.
        assertEquals(1L,((Number)begun.get("expectedBindingVersion")).longValue());
        service.record(7L,"as_test",scope.runId(),"delta",Map.of("text","PRIVATE"));
        service.record(7L,"as_test",scope.runId(),"reasoning",Map.of("text","PRIVATE"));
        fixture.scopes.update(7L,"as_test",new ScopeDtos.Update(1L,selection));
        service.record(7L,"as_test",scope.runId(),"error",Map.of("code","AUTHZ","message","Scope denied"));
        service.record(7L,"as_test",scope.runId(),"done",Map.of("finishReason","error"));
        var finished=service.finish(7L,"as_test",scope.runId());
        assertEquals("error",store.savedRuns.get(scope.runId()).getStatus());
        assertNotNull(store.savedRuns.get(scope.runId()).getFinishedAt());
        assertEquals("error",finished.get("status"));assertEquals("unavailable",finished.get("scopeState"));assertNull(finished.get("scope"));
        assertEquals("Original question",finished.get("input"));assertNotNull(finished.get("scopeSelection"));
        for(String key:List.of("answer","reasoning","toolCalls","trace","sources","artifacts","error","attachmentRefs")) assertFalse(finished.containsKey(key));
        assertFalse(finished.toString().contains("PRIVATE"));
        var fresh=fixture.scopes.authorize(7L,"as_test",scope.runId(),null,true,2L,null,true);
        service.replaceScope(7L,"as_test",scope.runId(),fresh);
        var restored=service.list(7L,"as_test").get(0);
        assertEquals(2L,((Number)restored.get("expectedBindingVersion")).longValue());
        assertEquals(finished.get("scopeSelection"),restored.get("scopeSelection"));
    }
    @Test void absentAuthorityNeverReturnsPrivateCacheButFinishStillPersists() {
        var store=new AgentWorkbenchTestStore();var service=store.runService();
        // This run never received a grant, but the authority itself exists and is live.
        var authority=new com.nexus.chat.agent.AgentScopeTestDouble();
        org.springframework.test.util.ReflectionTestUtils.setField(service,"scopes",authority.service());
        authority.bindOnly(7L,"as_test");
        var request=new AgentSessionAndChatDtos.SessionChatRequest();request.setInput("Question");
        service.begin(7L,"as_test","run_closed01",request);
        service.record(7L,"as_test","run_closed01","delta",Map.of("text","PRIVATE"));
        var view=service.finish(7L,"as_test","run_closed01");
        assertEquals("interrupted",view.get("status"));assertEquals("unavailable",view.get("scopeState"));
        assertNull(view.get("scope"));assertFalse(view.containsKey("answer"));assertFalse(view.toString().contains("PRIVATE"));
    }
}
