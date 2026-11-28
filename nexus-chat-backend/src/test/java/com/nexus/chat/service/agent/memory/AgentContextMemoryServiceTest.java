package com.nexus.chat.service.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.agent.AgentScopeWireFixture;
import com.nexus.chat.model.agent.*;
import com.nexus.chat.repository.agent.*;
import com.nexus.chat.service.agent.*;
import com.nexus.chat.service.agent.scope.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentContextMemoryServiceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final AgentRunRecordRepository runs=mock(AgentRunRecordRepository.class);
    final AgentSessionSummaryRepository summaries=mock(AgentSessionSummaryRepository.class);
    final AgentScopeService scopes=mock(AgentScopeService.class);
    final ScopeDtos.AuthorizedScope scope=AgentScopeWireFixture.scope(7L,"session-memory","run-current");
    final AgentContextMemoryService service=new AgentContextMemoryService(runs,summaries,scopes,mapper);
    AgentRunRecord record(long id,ScopeDtos.AuthorizedScope s) throws Exception {
        var r=new AgentRunRecord();r.setId(id);r.setUserId(s.actorUserId());r.setSessionId(s.sessionId());r.setRunId("run-"+id);r.setStatus("done");
        r.setPayloadJson(mapper.writeValueAsString(Map.of("scope",s,"input","actual user input "+id,"answer","actual answer "+id)));return r;
    }
    @Test void databaseHistoryIsUsedWithoutAnyRedisDependency() throws Exception {
        when(runs.findRecent(eq(7L),eq("session-memory"),any(Pageable.class))).thenReturn(List.of(record(1,scope)));
        var result=service.context(scope);assertEquals("mysql",result.get("source"));
        assertTrue(result.get("messages").toString().contains("actual user input 1"));verify(scopes).validate(7L,"session-memory","run-current",scope);
    }
    @Test void mismatchedScopeCannotContributeHistory() throws Exception {
        var other=AgentScopeWireFixture.scope(8L,"session-memory","run-other");
        when(runs.findRecent(eq(7L),eq("session-memory"),any())).thenReturn(List.of(record(1,other)));
        assertEquals(List.of(),service.context(scope).get("messages"));verify(summaries,never()).save(any());
    }
    @Test void sourceSuppressionRemovesRawHistoryAndPreventsNewSummary() throws Exception {
        var row=record(1,scope);row.setMemorySuppressed(true);when(runs.findRecent(eq(7L),eq("session-memory"),any())).thenReturn(List.of(row));
        assertEquals(List.of(),service.context(scope).get("messages"));verify(summaries,never()).save(any());
    }
    @Test void rollingSummaryPersistsCoverageAndKeepsNewestSixExchanges() throws Exception {
        var list=new ArrayList<AgentRunRecord>();for(long id=8;id>=1;id--)list.add(record(id,scope));
        when(runs.findRecent(eq(7L),eq("session-memory"),any())).thenReturn(list);
        when(runs.findSummarySource(eq(7L),eq("session-memory"),eq(0L),eq(3L),any())).thenReturn(List.of(list.get(7),list.get(6)));
        var result=service.context(scope);assertEquals(12,((List<?>)result.get("messages")).size());
        assertTrue(result.get("summary").toString().contains("actual user input 1"));assertFalse(result.get("summary").toString().contains("actual user input 8"));
        verify(summaries).save(argThat(s -> s.getCoveredFromMsgId()==1 && s.getCoveredToMsgId()==2 && scope.scopeKey().equals(s.getScopeKey())));
    }
    @Test void revokedGrantFailsBeforeAnySourceRead() {
        doThrow(new IllegalStateException("revoked")).when(scopes).validate(7L,"session-memory","run-current",scope);
        assertThrows(IllegalStateException.class,()->service.context(scope));verifyNoInteractions(runs,summaries);
    }
    @Test void vectorRevisionIsCaseSensitiveAndSimilarityHandlesHugeFiniteNumbers() {
        var a=new AgentProviderService.ResolvedProvider(1L,"openai","fixture","https://example.org/v1","Embed","fixture-KEY");
        var b=new AgentProviderService.ResolvedProvider(1L,"openai","fixture","https://example.org/v1","embed","fixture-key");
        assertNotEquals(MemoryVectorSupport.revision(a),MemoryVectorSupport.revision(b));
        assertEquals(1,MemoryVectorSupport.cosine(List.of(1e300,1e300),List.of(1e300,1e300)),1e-9);
    }
}
