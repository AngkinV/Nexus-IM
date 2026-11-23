package com.nexus.chat.agent;

import com.nexus.chat.model.Chat;
import com.nexus.chat.model.ChatMember;
import com.nexus.chat.model.Message;
import com.nexus.chat.model.User;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.repository.ChatRepository;
import com.nexus.chat.repository.MessageRepository;
import com.nexus.chat.repository.UserRepository;
import com.nexus.chat.service.agent.AgentToolService;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The fixed world the agent-tool tests and {@link McpHarnessMain} share:
 * alice (7) and bob (9) are the two members of direct chat 5, which holds two
 * messages; carol (8) is a member of nothing. Backed by Mockito, no database.
 */
public record AgentToolFixture(ChatMemberRepository members, MessageRepository messages,
                               ChatRepository chats, UserRepository users) {

    public static final long ALICE = 7, CAROL = 8, BOB = 9, CHAT = 5, FIRST_MESSAGE = 101, SECOND_MESSAGE = 102;

    private static final java.util.Map<AgentToolFixture,Authority> AUTHORITIES = new java.util.IdentityHashMap<>();
    private record Authority(com.nexus.chat.service.agent.scope.AgentScopeService scopes,
                             com.nexus.chat.service.agent.scope.AgentScopeResources resources,
                             java.util.Map<Long,String> headers) {}
    private Authority authority() {
        return AUTHORITIES.computeIfAbsent(this,f->{
            var b=mock(com.nexus.chat.repository.agent.AgentScopeBindingRepository.class);
            var g=mock(com.nexus.chat.repository.agent.AgentScopeGrantRepository.class);
            var c=mock(com.nexus.chat.repository.ContactRepository.class);
            var bs=new java.util.HashMap<String,com.nexus.chat.model.agent.AgentScopeBinding>();
            var gs=new java.util.HashMap<String,com.nexus.chat.model.agent.AgentScopeGrant>();
            when(b.lockBySessionId(any())).thenAnswer(i->Optional.ofNullable(bs.get(i.getArgument(0))));
            when(b.saveAndFlush(any())).thenAnswer(i->{com.nexus.chat.model.agent.AgentScopeBinding v=i.getArgument(0);bs.put(v.sessionId,v);return v;});
            when(g.findById(any())).thenAnswer(i->Optional.ofNullable(gs.get(i.getArgument(0))));
            when(g.findBySessionIdAndRunId(any(),any())).thenReturn(List.of());
            when(g.saveAndFlush(any())).thenAnswer(i->{com.nexus.chat.model.agent.AgentScopeGrant v=i.getArgument(0);gs.put(v.grantId,v);return v;});
            var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            var scopes=new com.nexus.chat.service.agent.scope.AgentScopeService(b,g,members,chats,c,json);
            var headers=new java.util.HashMap<Long,String>();
            for(long actor:List.of(ALICE,BOB)) {
                long other=actor==ALICE?BOB:ALICE;when(c.existsByUserIdAndContactUserId(actor,other)).thenReturn(true);
                var selection=new com.nexus.chat.service.agent.scope.ScopeDtos.Selection(List.of(new com.nexus.chat.service.agent.scope.ScopeDtos.Target("contact",other)),com.nexus.chat.service.agent.scope.ScopeDtos.TimeRange.unbounded());
                String session="fixture_"+actor;scopes.create(actor,session,selection);
                var scope=scopes.authorize(actor,session,"fixture_run",null,true,1L,null,false);
                try {headers.put(actor,java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(scope)));} catch(Exception e){throw new IllegalStateException(e);}
            }
            return new Authority(scopes,new com.nexus.chat.service.agent.scope.AgentScopeResources(c,chats,members,users),headers);
        });
    }
    public String scopeHeader(long actor) { return authority().headers().get(actor); }
    /** Candidate listing lives in AgentScopeResources, not on the tool service. */
    public com.nexus.chat.service.agent.scope.AgentScopeResources resources() { return authority().resources(); }

    /** Run a direct service call under the same runtime scope the MCP registry installs. */
    public <T> T inScope(long actor, java.util.concurrent.Callable<T> call) {
        try (var ignored = com.nexus.chat.service.agent.scope.AgentScopeService.runtime(scopeHeader(actor))) {
            return call.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    public AgentToolService service() {
        var service=new AgentToolService(members, messages, chats, users);
        service.setScopeService(authority().scopes());
        org.mockito.Mockito.clearInvocations(members,messages,chats,users);return service;
    }

    public static AgentToolFixture create() {
        AgentToolFixture fixture = new AgentToolFixture(mock(ChatMemberRepository.class),
                mock(MessageRepository.class), mock(ChatRepository.class), mock(UserRepository.class));

        for (User user : List.of(user(ALICE, "alice"), user(CAROL, "carol"), user(BOB, "bob"))) {
            when(fixture.users.findById(user.getId())).thenReturn(Optional.of(user));
            when(fixture.users.existsById(user.getId())).thenReturn(true);
            when(fixture.users.findByUsername(user.getUsername())).thenReturn(Optional.of(user));
            if(user.getId()!=CAROL) {
                var p=mock(UserRepository.AgentCandidate.class);when(p.getId()).thenReturn(user.getId());when(p.getUsername()).thenReturn(user.getUsername());
                for(long actor:List.of(ALICE,BOB)) when(fixture.users.findAgentCandidates(actor,user.getUsername())).thenReturn(List.of(p));
            }
        }

        Chat chat = new Chat();
        chat.setId(CHAT);
        chat.setType(Chat.ChatType.direct);
        chat.setCreatedBy(ALICE);
        chat.setLastMessageAt(LocalDateTime.of(2026, 11, 20, 9, 31));
        when(fixture.chats.findById(CHAT)).thenReturn(Optional.of(chat));
        when(fixture.chats.findByUserIdOrderByLastMessageAtDesc(ALICE)).thenReturn(List.of(chat));
        when(fixture.chats.findByUserIdOrderByLastMessageAtDesc(BOB)).thenReturn(List.of(chat));
        when(fixture.chats.findDirectChatBetweenUsers(ALICE, BOB)).thenReturn(Optional.of(chat));
        when(fixture.chats.findDirectChatBetweenUsers(BOB, ALICE)).thenReturn(Optional.of(chat));

        when(fixture.members.existsByChatIdAndUserId(CHAT, ALICE)).thenReturn(true);
        when(fixture.members.existsByChatIdAndUserId(CHAT, BOB)).thenReturn(true);
        when(fixture.members.findByChatId(CHAT)).thenReturn(List.of(member(ALICE), member(BOB)));

        Message first = message(FIRST_MESSAGE, ALICE, "hi bob", LocalDateTime.of(2026, 11, 20, 9, 30));
        Message second = message(SECOND_MESSAGE, BOB, "hello alice", LocalDateTime.of(2026, 11, 20, 9, 31));
        when(fixture.messages.findById(FIRST_MESSAGE)).thenReturn(Optional.of(first));
        when(fixture.messages.findById(SECOND_MESSAGE)).thenReturn(Optional.of(second));
        when(fixture.messages.findByChatIdOrderByCreatedAtDesc(eq(CHAT), any(Pageable.class))).thenAnswer(inv ->
                new PageImpl<>(List.of(second, first).subList(0, Math.min(2, inv.<Pageable>getArgument(1).getPageSize()))));
        when(fixture.messages.findScopedMessages(eq(CHAT),any(),any(),any(Pageable.class))).thenAnswer(inv ->
                new PageImpl<>(List.of(second, first).subList(0,Math.min(2,inv.<Pageable>getArgument(3).getPageSize()))));
        for(var message:List.of(first,second)) {
            var p=mock(MessageRepository.ScopeAttribution.class);when(p.getChatId()).thenReturn(CHAT);when(p.getCreatedAt()).thenReturn(message.getCreatedAt());
            when(fixture.messages.findScopeAttribution(message.getId())).thenReturn(Optional.of(p));
        }
        return fixture;
    }

    private static User user(long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private static ChatMember member(long userId) {
        ChatMember member = new ChatMember();
        member.setChatId(CHAT);
        member.setUserId(userId);
        return member;
    }

    private static Message message(long id, long senderId, String content, LocalDateTime createdAt) {
        Message message = new Message();
        message.setId(id);
        message.setChatId(CHAT);
        message.setSenderId(senderId);
        message.setContent(content);
        message.setCreatedAt(createdAt);
        return message;
    }
}
