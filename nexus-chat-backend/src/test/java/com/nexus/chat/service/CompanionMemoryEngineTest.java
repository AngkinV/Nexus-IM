package com.nexus.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import com.nexus.chat.service.agent.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompanionMemoryEngineTest {
    final CompanionMemoryRepository memories = mock(CompanionMemoryRepository.class);
    final CompanionMessageRepository messages = mock(CompanionMessageRepository.class);
    final TavernSessionRepository sessions = mock(TavernSessionRepository.class);
    final AgentProviderService providers = mock(AgentProviderService.class);
    final PythonModelGateway gateway = mock(PythonModelGateway.class);
    final ObjectMapper mapper = new ObjectMapper();
    final CompanionMemoryEngine engine = new CompanionMemoryEngine(memories,messages,sessions,providers,gateway,mapper);
    CompanionMemory row() {
        CompanionMemory m = new CompanionMemory(); m.setId(7L); m.setUserId(1L); m.setRoleId(2L);
        m.setContent("用户喜欢茶"); m.setConfirmed(true);
        when(memories.findActive(eq(1L),eq(2L),any(Pageable.class))).thenReturn(List.of(m));
        when(memories.findByIdAndUserId(7L,1L)).thenReturn(Optional.of(m));
        when(memories.countActive(1L,2L)).thenReturn(1L); return m;
    }
    @Test void lowSensitivitySourceOnly() {
        assertNotNull(CompanionMemorySafety.candidate("我喜欢喝茶"));
        assertNotNull(CompanionMemorySafety.candidate("I prefer short replies"));
        for (String s : List.of("我喜欢公司的地址", "我喜欢健康相关话题", "我喜欢 token=private-fixture", "我喜欢 123456", "她喜欢喝茶", "我喜欢\n喝茶"))
            assertNull(CompanionMemorySafety.candidate(s),s);
    }
    @Test void unknownPersonalMedicalOrFinancialStatementsAreNotAutomaticCandidates() {
        for(String raw:List.of("I like Alex","I prefer taking insulin","我喜欢妈妈做的饭","我喜欢理财","I like investing in crypto","I prefer short replies, my name is Alex")) assertNull(CompanionMemorySafety.candidate(raw),raw);
        assertTrue(CompanionMemorySafety.candidate("i prefer short replies").startsWith("User stated: "));
    }
    @Test void absentEmbeddingStillRecallsConfirmedMemories() {
        row(); when(providers.resolveEmbeddingCredential(1L,null)).thenReturn(Optional.empty());
        var r=engine.recall(1L,2L,"喝什么"); assertEquals("unconfigured",r.status()); assertEquals(List.of(7L),r.ids());
        verifyNoInteractions(gateway);
    }
    @Test void finiteSimilarityAndDimensions() {
        assertEquals(1,CompanionMemoryEngine.cosine(List.of(1d,0d),List.of(1d,0d)),1e-9);
        assertEquals(0,CompanionMemoryEngine.cosine(List.of(0d),List.of(0d)));
        assertEquals(0,CompanionMemoryEngine.cosine(List.of(1d),List.of(1d,2d)));
    }
    @Test void accountEmbeddingIsActuallyStored() throws Exception {
        var m=row(); var cfg=new AgentProviderService.ResolvedProvider(3L,"openai","test","https://example.org/v1","embed-test","fixture-only");
        when(providers.resolveEmbeddingCredential(1L,null)).thenReturn(Optional.of(cfg));
        when(gateway.embed(eq(1L),same(cfg),anyList(),eq(15))).thenReturn(mapper.readTree("{\"ok\":true,\"vectors\":[[1,0],[1,0]]}"));
        var r=engine.recall(1L,2L,"茶"); assertEquals("ready",r.status()); assertNotNull(m.getEmbeddingRevision());
        assertEquals("[1,0]",m.getEmbeddingVector()); verify(memories).save(m);
    }
    @Test void changedEmbeddingCannotWriteOldVectors() throws Exception {
        var m=row(); var a=new AgentProviderService.ResolvedProvider(3L,"openai","test","https://example.org/v1","a","fixture-only");
        var b=new AgentProviderService.ResolvedProvider(3L,"openai","test","https://example.org/v1","b","fixture-only");
        when(providers.resolveEmbeddingCredential(1L,null)).thenReturn(Optional.of(a),Optional.of(b));
        when(gateway.embed(eq(1L),same(a),anyList(),eq(15))).thenReturn(mapper.readTree("{\"ok\":true,\"vectors\":[[1,0],[1,0]]}"));
        assertEquals("rebuilding",engine.recall(1L,2L,"茶").status()); assertNull(m.getEmbeddingVector()); verify(memories,never()).save(any());
    }
    @Test void forgottenSourceCannotBeRecaptured() {
        CompanionMessage src=new CompanionMessage(); src.setId(10L); src.setUserId(1L); src.setRoleId(2L);
        src.setSenderType(CompanionMessage.SenderType.user); src.setContent("我喜欢喝茶");
        when(memories.existsByUserIdAndRoleIdAndSourceHash(eq(1L),eq(2L),anyString())).thenReturn(true);
        engine.capture(src); verify(memories,never()).save(any());
        src.setSenderType(CompanionMessage.SenderType.role); engine.capture(src); verify(memories,never()).save(any());
    }
    @Test void forgetQuarantinesOldContextButKeepsArchive() {
        var m=row(); CompanionMessage last=new CompanionMessage(); last.setId(40L);
        when(messages.findLatestForRole(1L,2L)).thenReturn(Optional.of(last)); engine.forget(1L,m);
        assertTrue(m.getDeleted()); assertNull(m.getEmbeddingVector()); verify(sessions).invalidateSummaries(1L,2L,40L);
        verify(messages,never()).deleteHistory(anyLong(),anyLong());
    }
    @Test void rollingSummaryPersistsUserSourcesOnly() {
        TavernSession s=new TavernSession(); s.setId(10L); s.setUserId(1L); s.setRoleId(2L);
        CompanionMessage latest=new CompanionMessage(); latest.setId(50L); latest.setContent("当前一轮");
        CompanionMessage user=new CompanionMessage(); user.setId(1L); user.setSenderType(CompanionMessage.SenderType.user); user.setContent("我喜欢喝茶");
        CompanionMessage role=new CompanionMessage(); role.setId(2L); role.setSenderType(CompanionMessage.SenderType.role); role.setContent("虚构的用户事实");
        when(messages.findBySessionIdOrderByIdDesc(eq(10L),any())).thenReturn(List.of(latest));
        when(messages.findSummarySource(eq(1L),eq(10L),eq(0L),eq(50L),any())).thenReturn(List.of(user,role));
        engine.history(s,4000); assertTrue(s.getRollingSummary().contains("我喜欢喝茶")); assertFalse(s.getRollingSummary().contains("虚构"));
        assertEquals(2L,s.getSummaryThroughId()); verify(sessions).save(s);
    }
    @Test void forgetWaitsForTheConversationRatherThanLettingOldRepliesReviveMemory() {
        var m=row();var service=mock(CompanionService.class,org.mockito.Mockito.CALLS_REAL_METHODS);var guard=new CompanionConversationGuard();
        org.springframework.test.util.ReflectionTestUtils.setField(service,"memoryRepository",memories);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"memoryEngine",engine);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"memoryWriteGuard",guard);
        try(var ignored=guard.acquire(1L,2L)) {
            assertEquals(409,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.deleteMemory(1L,7L)).getStatusCode().value());
            verify(memories,never()).save(any());
        }
        service.deleteMemory(1L,7L);assertTrue(m.getDeleted());assertEquals("",m.getContent());
    }
    @Test void correctionIsExplicitAndReferencesTheOldItemWithoutReusingItsVector() {
        var old=row();old.setEmbeddingVector("[1,0]");
        var corrected=engine.correct(1L,7L,"用户现在喜欢详细回复");
        assertTrue(old.getDeleted());assertEquals("",old.getContent());assertEquals(7L,corrected.getCorrectsId());
        assertTrue(corrected.getConfirmed());assertNull(corrected.getEmbeddingVector());assertEquals("用户现在喜欢详细回复",corrected.getContent());
    }

    private CompanionMessage source(String text) {
        var m=new CompanionMessage();m.setId(33L);m.setUserId(1L);m.setRoleId(2L);m.setSenderType(CompanionMessage.SenderType.user);m.setContent(text);return m;
    }

    @Test void sourceDedupIsPerMessageSoAnExplicitNewStatementAfterForgetIsNotSilentlyBlocked() {
        var a=source("我喜欢简短回复");engine.capture(a);
        a.setMemoryProcessed(false);when(memories.existsByUserIdAndRoleIdAndSourceHash(1L,2L,CompanionMemorySafety.hash("message:"+a.getId()+":"+CompanionMemorySafety.candidate(a.getContent())))).thenReturn(true);
        engine.capture(a);verify(memories,times(1)).save(any());
        var b=source("我喜欢简短回复");b.setId(34L);engine.capture(b);verify(memories,times(2)).save(any());
    }

}
