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

class AgentSemanticMemoryServiceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final AgentLongMemoryRepository memories=mock(AgentLongMemoryRepository.class);
    final AgentRunRecordRepository runs=mock(AgentRunRecordRepository.class);
    final AgentMemoryAuditRepository audits=mock(AgentMemoryAuditRepository.class);
    final AgentScopeService scopes=mock(AgentScopeService.class);
    final AgentProviderService providers=mock(AgentProviderService.class);
    final PythonModelGateway gateway=mock(PythonModelGateway.class);
    final ScopeDtos.AuthorizedScope scope=AgentScopeWireFixture.scope(7L,"session-memory","run-current");
    final AgentSemanticMemoryService service=new AgentSemanticMemoryService(memories,runs,audits,scopes,providers,gateway,mapper);
    AgentLongMemory row(){
        var m=new AgentLongMemory();m.setId(7L);m.setUserId(7L);m.setContent("用户喜欢简短回复");m.setConfirmed(false);m.setIsActive(true);m.setScopeKey(scope.scopeKey());m.setSessionGeneration(scope.sessionGeneration());m.setSourceSessionId(scope.sessionId());
        when(memories.findRecall(eq(7L),eq(scope.sessionId()),eq(scope.scopeKey()),eq(scope.sessionGeneration()),any(Pageable.class))).thenReturn(List.of(m));when(memories.findById(7L)).thenReturn(Optional.of(m));return m;
    }
    @Test void unconfiguredAccountFallsBackAndDoesNotUseGlobalModel(){
        row();when(providers.resolveEmbeddingCredential(7L,null)).thenReturn(Optional.empty());
        var r=service.recall(scope,"回复");assertEquals("unconfigured",r.status());assertEquals(List.of(7L),r.ids());assertTrue(r.lines().get(0).contains("未确认"));verifyNoInteractions(gateway);
    }
    @Test void embeddingUsesExplicitOwnerAndPersistsOnlyCompatibleVectors() throws Exception {
        var m=row();var cfg=new AgentProviderService.ResolvedProvider(3L,"openai","fixture","https://example.org/v1","embedding","fixture-key");
        when(providers.resolveEmbeddingCredential(7L,null)).thenReturn(Optional.of(cfg));when(gateway.embed(eq(7L),same(cfg),anyList(),eq(15))).thenReturn(mapper.readTree("{\"ok\":true,\"vectors\":[[1,0],[1,0]]}"));
        var r=service.recall(scope,"回复");assertEquals("ready",r.status());assertEquals(1,r.indexed());assertNotNull(m.getEmbeddingRevision());verify(memories).save(m);
    }
    @Test void credentialChangesCannotWriteOrMixOldVectors() throws Exception {
        var m=row();var a=new AgentProviderService.ResolvedProvider(3L,"openai","fixture","https://example.org/v1","a","fixture-key");var b=new AgentProviderService.ResolvedProvider(3L,"openai","fixture","https://example.org/v1","b","fixture-key");
        when(providers.resolveEmbeddingCredential(7L,null)).thenReturn(Optional.of(a),Optional.of(b));when(gateway.embed(eq(7L),same(a),anyList(),eq(15))).thenReturn(mapper.readTree("{\"ok\":true,\"vectors\":[[1,0],[1,0]]}"));
        assertEquals("rebuilding",service.recall(scope,"回复").status());assertNull(m.getEmbeddingVector());verify(memories,never()).save(any());
    }
    @Test void automaticCandidateComesFromUserNotRoleAnswerAndHasIdempotentSource() throws Exception {
        var run=new AgentRunRecord();run.setId(1L);run.setRunId("run-source");run.setPayloadJson(mapper.writeValueAsString(Map.of("scope",scope,"input","我喜欢简短回复","answer","角色虚构用户经历")));
        when(runs.findPendingMemory(eq(7L),eq(scope.sessionId()),any())).thenReturn(List.of(run));when(memories.saveAndFlush(any())).thenAnswer(i->{AgentLongMemory m=i.getArgument(0);m.setId(8L);return m;});
        service.recover(scope);verify(memories).saveAndFlush(argThat(m -> m.getContent().equals("用户表示：我喜欢简短回复") && !m.getConfirmed() && m.getSourceRunId().equals("run-source")));verify(runs).markMemoryProcessed(1L);
        when(memories.existsByUserIdAndScopeKeyAndSourceHash(eq(7L),eq(scope.scopeKey()),anyString())).thenReturn(true);service.recover(scope);verify(memories,times(1)).saveAndFlush(any());
    }
    @Test void revokedScopeFailsBeforeReadingOrWritingSource(){
        doThrow(new IllegalStateException("revoked")).when(scopes).validate(7L,scope.sessionId(),scope.runId(),scope);
        assertThrows(IllegalStateException.class,()->service.recover(scope));assertThrows(IllegalStateException.class,()->service.recall(scope,"hello"));verifyNoInteractions(memories,runs,audits,gateway);
    }
}
