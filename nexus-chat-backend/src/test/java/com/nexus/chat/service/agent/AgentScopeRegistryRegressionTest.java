package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.model.*;
import com.nexus.chat.model.agent.*;
import com.nexus.chat.repository.*;
import com.nexus.chat.repository.agent.*;
import com.nexus.chat.service.agent.scope.*;
import com.nexus.chat.service.agent.tool.*;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.nexus.chat.service.agent.scope.ScopeDtos.*;

class AgentScopeRegistryRegressionTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final AgentScopeBindingRepository bindings=mock(AgentScopeBindingRepository.class);
    final AgentScopeGrantRepository grants=mock(AgentScopeGrantRepository.class);
    final ChatMemberRepository members=mock(ChatMemberRepository.class);
    final ChatRepository chats=mock(ChatRepository.class);
    final ContactRepository contacts=mock(ContactRepository.class);
    final MessageRepository messages=mock(MessageRepository.class);
    final UserRepository users=mock(UserRepository.class);
    final Map<String,AgentScopeBinding> bs=new HashMap<>();
    final Map<String,AgentScopeGrant> gs=new HashMap<>();
    final AgentScopeService scopes=new AgentScopeService(bindings,grants,members,chats,contacts,json);
    final AgentToolService tools=new AgentToolService(members,messages,chats,users);
    final AgentToolRegistry registry;
    AgentScopeRegistryRegressionTest() {
        tools.setScopeService(scopes); registry=new AgentToolRegistry(List.of(tools));
        when(bindings.lockBySessionId(anyString())).thenAnswer(i->Optional.ofNullable(bs.get(i.getArgument(0))));
        when(bindings.saveAndFlush(any())).thenAnswer(i->{AgentScopeBinding b=i.getArgument(0);bs.put(b.sessionId,b);return b;});
        when(grants.findById(anyString())).thenAnswer(i->Optional.ofNullable(gs.get(i.getArgument(0))));
        when(grants.findBySessionIdAndRunId(anyString(),any())).thenAnswer(i->gs.values().stream().filter(g->g.sessionId.equals(i.getArgument(0))&&Objects.equals(g.runId,i.getArgument(1))).toList());
        when(grants.findBySessionId(anyString())).thenAnswer(i->gs.values().stream().filter(g->g.sessionId.equals(i.getArgument(0))).toList());
        when(grants.save(any())).thenAnswer(i->{AgentScopeGrant g=i.getArgument(0);gs.put(g.grantId,g);return g;});
        when(grants.saveAndFlush(any())).thenAnswer(i->{AgentScopeGrant g=i.getArgument(0);gs.put(g.grantId,g);return g;});
        for(long id:List.of(101L,202L)) {
            var c=new Chat();c.setId(id);c.setType(Chat.ChatType.group);c.setName("same-name");
            when(chats.findById(id)).thenReturn(Optional.of(c));
            when(members.existsByChatIdAndUserId(id,1L)).thenReturn(true);
        }
        var a=new Message();a.setId(11L);a.setChatId(101L);a.setSenderId(1L);a.setContent("A_ONLY");
        when(messages.findScopedMessages(eq(101L),any(),any(),any())).thenReturn(new PageImpl<>(List.of(a)));
        var projection=mock(MessageRepository.ScopeAttribution.class);when(projection.getChatId()).thenReturn(202L);
        when(messages.findScopeAttribution(22L)).thenReturn(Optional.of(projection));
        scopes.create(1L,"session",selection(101L));
    }
    Selection selection(Long... ids) {return new Selection(Arrays.stream(ids).map(id->new Target("group",id)).toList(),TimeRange.unbounded());}
    AuthorizedScope grant() {return scopes.authorize(1L,"session","run_00001",null,true,1L,null,false);}
    McpSchema.CallToolResult call(String tool, String actor, AuthorizedScope scope, Map<String,Object> args) throws Exception {
        var ctx=new HashMap<String,Object>();ctx.put(AgentToolRegistry.CTX_ACTOR,actor);
        if(scope!=null)ctx.put(AgentToolRegistry.CTX_SCOPE,Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsString(scope).getBytes(StandardCharsets.UTF_8)));
        return registry.specifications().stream().filter(s->s.tool().name().equals(tool)).findFirst().orElseThrow().callHandler().apply(McpTransportContext.create(ctx),new McpSchema.CallToolRequest(tool,args,null));
    }
    void denied(McpSchema.CallToolResult result) {
        assertEquals(Boolean.TRUE,result.isError());assertNull(result.structuredContent());
        assertFalse(result.content().toString().contains("A_ONLY"));assertFalse(result.content().toString().contains("B_ONLY"));
        verify(messages,never()).findScopedMessages(eq(202L),any(),any(),any());
        verify(messages,never()).findById(22L);
    }
    @Test void positiveControlAndZeroBodyReadForOutOfScopeAndProjection() throws Exception {
        var s=grant();var a=call("get_recent_messages","1",s,Map.of("chat_id",101L));
        assertFalse(Boolean.TRUE.equals(a.isError()));assertTrue(a.content().toString().contains("A_ONLY"));
        verify(messages,times(1)).findScopedMessages(eq(101L),any(),any(),any());
        denied(call("get_recent_messages","1",s,Map.of("chat_id",202L)));
        denied(call("get_message_by_id","1",s,Map.of("message_id",22L)));
        verify(messages,times(1)).findScopeAttribution(22L);
    }
    @Test void missingCrossAccountForgedVersionExpandedScopeAndExitNeverReadBody() throws Exception {
        var s=grant();denied(call("get_recent_messages","2",s,Map.of("chat_id",101L)));
        denied(call("get_recent_messages","1",null,Map.of("chat_id",101L)));
        var forged=new AuthorizedScope(1,s.grantId(),1L,"session",s.runId(),1,99,1,s.chatIds(),s.contactUserIds(),s.timeRange(),s.scopeKey());
        denied(call("get_recent_messages","1",forged,Map.of("chat_id",101L)));
        var expanded=new AuthorizedScope(1,s.grantId(),1L,"session",s.runId(),1,1,1,List.of(101L,202L),List.of(),s.timeRange(),AgentScopeService.key(1L,"session",1,List.of(101L,202L),List.of(),s.timeRange()));
        denied(call("get_recent_messages","1",expanded,Map.of("chat_id",202L)));
        when(members.existsByChatIdAndUserId(101L,1L)).thenReturn(false);
        denied(call("get_recent_messages","1",s,Map.of("chat_id",101L)));
        verify(messages,never()).findScopedMessages(eq(101L),any(),any(),any());
    }
    @Test void emptyScopeAndEmptyResultAreDistinct() throws Exception {
        when(messages.findScopedMessages(eq(101L),any(),any(),any())).thenReturn(new PageImpl<>(List.of()));
        var s=grant();var result=call("get_recent_messages","1",s,Map.of("chat_id",101L));
        assertFalse(Boolean.TRUE.equals(result.isError()));assertTrue(result.content().toString().contains("messages"));
        verify(messages,times(1)).findScopedMessages(eq(101L),any(),any(),any());
        var empty=scopes.authorize(1L,"session","run_00002",selection(),true,1L,null,false);
        denied(call("get_recent_messages","1",empty,Map.of("chat_id",202L)));
    }
    @Test void resumeRevokesOldGrantRetryIsNewAndDeleteBlocksLateReads() throws Exception {
        var s=grant();var resumed=scopes.authorize(1L,"session",s.runId(),null,true,1L,null,true);
        assertEquals(s.scopeKey(),resumed.scopeKey());assertEquals(2,resumed.authorizationVersion());
        denied(call("get_recent_messages","1",s,Map.of("chat_id",101L)));
        var retry=scopes.authorize(1L,"session","run_00003",null,true,1L,s.runId(),false);
        assertNotEquals(s.runId(),retry.runId());assertEquals(List.of(101L),retry.chatIds());
        scopes.invalidate(1L,"session",true);
        denied(call("get_recent_messages","1",retry,Map.of("chat_id",101L)));
        verify(messages,never()).findScopedMessages(eq(101L),any(),any(),any());
        assertTrue(bs.get("session").tombstone);assertEquals(2,bs.get("session").sessionGeneration);
        scopes.revokeSession(1L,"session");
        assertTrue(gs.values().stream().noneMatch(g->g.active));
    }
            @Test void displayTimezoneCannotChangeActualRepositoryParameters() throws Exception {
        var from=java.time.Instant.parse("2026-11-20T00:00:00Z"); var to=from.plusSeconds(3600);
        var range=new TimeRange(from,to,"Asia/Shanghai");
        var a=scopes.authorize(1L,"session","run_zone1",new Selection(List.of(new Target("group",101L)),range),true,1L,null,false);
        var b=scopes.authorize(1L,"session","run_zone2",new Selection(List.of(new Target("group",101L)),new TimeRange(from,to,"America/New_York")),true,1L,null,false);
        assertEquals(a.scopeKey(),b.scopeKey());
        assertTrue(call("get_recent_messages","1",a,Map.of("chat_id",101L)).content().toString().contains("A_ONLY"));
        assertTrue(call("get_recent_messages","1",b,Map.of("chat_id",101L)).content().toString().contains("A_ONLY"));
        verify(messages,times(2)).findScopedMessages(eq(101L),eq(java.time.LocalDateTime.of(2026,11,20,8,0)),eq(java.time.LocalDateTime.of(2026,11,20,9,0)),any());
        String encoded=json.writeValueAsString(range); assertTrue(encoded.contains("2026-11-20T00:00:00Z"));
        assertEquals("Asia/Shanghai",new TimeRange(null,null,"").timeZone());
    }
    @Test void messageProjectionClosedOpenBoundsReadOnlyInside() throws Exception {
        var from=java.time.Instant.parse("2026-11-20T00:00:00Z");
        var s=scopes.authorize(1L,"session","run_bounds",new Selection(List.of(new Target("group",101L)),new TimeRange(from,from.plusSeconds(3600),"UTC")),true,1L,null,false);
        for(long id:List.of(31L,32L,33L)) {
            var p=mock(MessageRepository.ScopeAttribution.class);when(p.getChatId()).thenReturn(101L);
            when(p.getCreatedAt()).thenReturn(java.time.LocalDateTime.of(2026,11,20,8,0).plusSeconds(id==31?0:id==32?3600:-1));
            when(messages.findScopeAttribution(id)).thenReturn(Optional.of(p));
        }
        var a=new Message();a.setId(31L);a.setChatId(101L);a.setSenderId(1L);a.setContent("A_ONLY");when(messages.findById(31L)).thenReturn(Optional.of(a));
        assertTrue(call("get_message_by_id","1",s,Map.of("message_id",31L)).content().toString().contains("A_ONLY"));
        denied(call("get_message_by_id","1",s,Map.of("message_id",32L))); denied(call("get_message_by_id","1",s,Map.of("message_id",33L)));
        verify(messages,times(1)).findById(31L);verify(messages,never()).findById(32L);verify(messages,never()).findById(33L);
    }
    @Test void failedCleanupOldGrantClosedNewGenerationWorksAndDeleteIsIdempotent() throws Exception {
        var old=grant();scopes.invalidate(1L,"session",false);
        doThrow(new IllegalStateException("cleanup failure")).when(grants).flush();
        assertThrows(IllegalStateException.class,()->scopes.revokeSession(1L,"session"));
        denied(call("get_recent_messages","1",old,Map.of("chat_id",101L)));
        var fresh=scopes.authorize(1L,"session","run_fresh",null,true,1L,null,false);
        assertEquals(2,fresh.sessionGeneration());assertTrue(call("get_recent_messages","1",fresh,Map.of("chat_id",101L)).content().toString().contains("A_ONLY"));
        scopes.invalidate(1L,"session",true);scopes.invalidate(1L,"session",true);
        assertEquals(3,bs.get("session").sessionGeneration);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->scopes.invalidate(1L,"session",false));
        denied(call("get_recent_messages","1",fresh,Map.of("chat_id",101L)));
    }
    @Test void duplicateCandidateNamesAreNeverAutoSelected() throws Exception {
        when(users.existsById(1L)).thenReturn(true);
        var a=mock(UserRepository.AgentCandidate.class);when(a.getId()).thenReturn(8L);when(a.getUsername()).thenReturn("same");
        var b=mock(UserRepository.AgentCandidate.class);when(b.getId()).thenReturn(9L);when(b.getUsername()).thenReturn("same");
        when(users.findAgentCandidates(1L,"same")).thenReturn(List.of(a,b));
        denied(call("find_direct_chat_with_user","1",null,Map.of("username","same")));
        verify(chats,never()).findDirectChatBetweenUsers(anyLong(),anyLong());verify(messages,never()).findById(any());
    }
    @Test void malformedRuntimeScopeFailsClosedBeforeAnyBodyQuery() throws Exception {
        var s=grant();var tree=json.valueToTree(s);
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree).put("authorizationVersion",0);
        String header=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(tree));
        var ctx=McpTransportContext.create(Map.of(AgentToolRegistry.CTX_ACTOR,"1",AgentToolRegistry.CTX_SCOPE,header));
        var handler=registry.specifications().stream().filter(spec->spec.tool().name().equals("get_recent_messages")).findFirst().orElseThrow().callHandler();
        denied(handler.apply(ctx,new McpSchema.CallToolRequest("get_recent_messages",Map.of("chat_id",101L),null)));
        verify(messages,never()).findScopedMessages(eq(101L),any(),any(),any());
    }
    @Test void bindingChangeExplicitResumeRotatesReceiptWithoutRequiringOldActive() throws Exception {
        var old=grant();scopes.update(1L,"session",new Update(1L,selection(101L)));
        denied(call("get_recent_messages","1",old,Map.of("chat_id",101L)));
        var fresh=scopes.authorize(1L,"session",old.runId(),null,true,2L,null,true);
        assertEquals(old.scopeKey(),fresh.scopeKey());assertEquals(2,fresh.bindingVersion());
        assertEquals(2,fresh.authorizationVersion());assertNotEquals(old.grantId(),fresh.grantId());
        assertTrue(call("get_recent_messages","1",fresh,Map.of("chat_id",101L)).content().toString().contains("A_ONLY"));
        verify(messages,times(1)).findScopedMessages(eq(101L),any(),any(),any());
    }
    @Test void revokedBExplicitRetryAHasNonemptyBodyAndNoBQueryOrReturn() throws Exception {
        var old=scopes.authorize(1L,"session","run_ab001",selection(101L,202L),true,1L,null,false);
        when(members.existsByChatIdAndUserId(202L,1L)).thenReturn(false);
        denied(call("get_recent_messages","1",old,Map.of("chat_id",101L)));
        verify(messages,never()).findScopedMessages(eq(101L),any(),any(),any());
        var fresh=scopes.authorize(1L,"session","run_a0002",selection(101L),true,1L,old.runId(),false);
        assertEquals(List.of(101L),fresh.chatIds());
        var result=call("get_recent_messages","1",fresh,Map.of("chat_id",101L));
        assertTrue(result.content().toString().contains("A_ONLY"));assertFalse(result.content().toString().contains("B_ONLY"));
        denied(call("get_recent_messages","1",fresh,Map.of("chat_id",202L)));
        verify(messages,times(1)).findScopedMessages(eq(101L),any(),any(),any());
        verify(messages,never()).findScopedMessages(eq(202L),any(),any(),any());
    }
    @Test void oneShotExactChatPositiveAndRevokedAfterEnd() throws Exception {
        var s=scopes.authorizeOneShotChat(1L,"as_oneshot_fixture","run_oneshot",101L);
        assertEquals(List.of(101L),s.chatIds());assertTrue(call("get_recent_messages","1",s,Map.of("chat_id",101L)).content().toString().contains("A_ONLY"));
        denied(call("get_message_by_id","1",s,Map.of("message_id",22L)));
        scopes.invalidate(1L,s.sessionId(),true);scopes.revokeSession(1L,s.sessionId());
        denied(call("get_recent_messages","1",s,Map.of("chat_id",101L)));
    }
}
