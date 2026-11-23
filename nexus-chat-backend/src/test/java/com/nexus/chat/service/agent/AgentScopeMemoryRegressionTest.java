package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.agent.AgentScopeWireFixture;
import com.nexus.chat.model.agent.AgentLongMemory;
import com.nexus.chat.repository.agent.*;
import com.nexus.chat.service.agent.memory.AgentMemoryService;
import com.nexus.chat.service.agent.scope.AgentScopeService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Key/owner cleanup contract fixtures only; not executed and no vector deletion claim. */
class AgentScopeMemoryRegressionTest {
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    final AgentLongMemoryRepository memories=mock(AgentLongMemoryRepository.class);
    final AgentMemoryAuditRepository audits=mock(AgentMemoryAuditRepository.class);
    final AgentMemoryService service=new AgentMemoryService(redis,new ObjectMapper(),memories,audits);
    @Test void encodedSessionPrefixCannotGlobAnotherSessionOrActor() {
        String prefix=AgentMemoryService.sessionPrefix(7L,"s:*中文");
        String expected="agent:ctx:7:"+Base64.getUrlEncoder().withoutPadding().encodeToString("s:*中文".getBytes(java.nio.charset.StandardCharsets.UTF_8))+":scope:";
        assertEquals(expected,prefix);assertFalse(prefix.contains("*"));assertFalse(prefix.contains("="));
        when(redis.keys(prefix+"*")).thenReturn(Set.of(prefix+"a".repeat(64)+":messages",prefix+"a".repeat(64)+":run:r:user"));
        service.clearShortTerm(7L,"s:*中文");verify(redis).keys(expected+"*");
        assertEquals(AgentMemoryService.sessionPrefix(7L,"s")+"a".repeat(64)+":messages",
            AgentMemoryService.messagesKey(new com.nexus.chat.service.agent.scope.ScopeDtos.AuthorizedScope(1,"g",7L,"s","r",1,1,1,List.of(),List.of(),com.nexus.chat.service.agent.scope.ScopeDtos.TimeRange.unbounded(),"a".repeat(64))));
    }
    @Test void disableChecksOwnerAndResetDisablesBeyondEight() {
        var foreign=new AgentLongMemory();foreign.setId(99L);foreign.setUserId(8L);foreign.setIsActive(true);
        when(memories.findById(99L)).thenReturn(Optional.of(foreign));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.disableLongTerm(99L,7L,"delete"));verify(memories,never()).save(foreign);
        var all=new ArrayList<AgentLongMemory>();
        for(long id=1;id<=12;id++){var m=new AgentLongMemory();m.setId(id);m.setUserId(7L);m.setIsActive(true);all.add(m);when(memories.findById(id)).thenReturn(Optional.of(m));}
        when(memories.findByUserIdAndIsActiveTrue(7L)).thenReturn(all);service.resetLongTerm(7L);
        assertTrue(all.stream().noneMatch(AgentLongMemory::getIsActive));verify(memories,times(12)).save(any());
        verify(memories,never()).findTop8ByUserIdAndIsActiveTrueOrderByUpdatedAtDesc(anyLong());
    }
    @Test void redisFailureStillAttemptsOwnerSessionDerivedCleanupAndLateGrantRejectedBeforeWrite() {
        var m=new AgentLongMemory();m.setUserId(7L);m.setSourceSessionId("s");m.setIsActive(true);m.setContent("A_ONLY");
        when(memories.findByUserIdAndSourceSessionId(7L,"s")).thenReturn(List.of(m));
        when(redis.keys(AgentMemoryService.sessionPrefix(7L,"s")+"*")).thenThrow(new IllegalStateException("redis down"));
        assertThrows(IllegalStateException.class,()->service.purgeSession(7L,"s"));assertFalse(m.getIsActive());assertEquals("",m.getContent());verify(memories).save(m);
        var scopes=mock(AgentScopeService.class);ReflectionTestUtils.setField(service,"scopes",scopes);
        var old=AgentScopeWireFixture.scope(7L,"s","r");
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(scopes).validate(7L,"s","r",old);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.appendShortTermMessage(old,"user","late"));
        verify(redis,never()).opsForList();
    }
}
