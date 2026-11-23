package com.nexus.chat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.model.Chat;
import com.nexus.chat.model.ChatMember;
import com.nexus.chat.model.agent.AgentScopeBinding;
import com.nexus.chat.model.agent.AgentScopeGrant;
import com.nexus.chat.repository.*;
import com.nexus.chat.repository.agent.AgentScopeBindingRepository;
import com.nexus.chat.repository.agent.AgentScopeGrantRepository;
import com.nexus.chat.service.agent.scope.AgentScopeService;
import com.nexus.chat.service.agent.scope.ScopeDtos;
import org.mockito.stubbing.Answer;

import java.time.LocalDateTime;
import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * In-memory scope authority for controller/gateway tests that only need a real
 * grant to exist. It is a test double: it never stands in for production
 * behaviour, and the A/B read-count assertions live in the dedicated scope
 * regression tests that exercise the real service.
 */
public final class AgentScopeTestDouble {

    private final AgentScopeBindingRepository bindings = mock(AgentScopeBindingRepository.class);
    private final AgentScopeGrantRepository grants = mock(AgentScopeGrantRepository.class);
    private final Map<String, AgentScopeBinding> bindingsById = new HashMap<>();
    private final Map<String, AgentScopeGrant> grantsById = new HashMap<>();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AgentScopeService service;

    public AgentScopeTestDouble() {
        when(bindings.lockBySessionId(anyString())).thenAnswer(inv -> Optional.ofNullable(bindingsById.get(inv.<String>getArgument(0))));
        when(bindings.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(bindingsById.get(inv.<String>getArgument(0))));
        when(bindings.existsById(anyString())).thenAnswer(inv -> bindingsById.containsKey(inv.<String>getArgument(0)));
        when(bindings.saveAndFlush(any())).thenAnswer(inv -> {
            AgentScopeBinding b = inv.getArgument(0);
            bindingsById.put(b.sessionId, b);
            return b;
        });
        when(grants.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(grantsById.get(inv.<String>getArgument(0))));
        when(grants.findBySessionId(anyString())).thenAnswer(inv -> grantsById.values().stream()
                .filter(g -> g.sessionId.equals(inv.<String>getArgument(0))).toList());
        when(grants.findBySessionIdAndRunId(anyString(), any())).thenAnswer(inv -> grantsById.values().stream()
                .filter(g -> g.sessionId.equals(inv.<String>getArgument(0)) && Objects.equals(g.runId, inv.getArgument(1)))
                .toList());
        when(grants.save(any())).thenAnswer(store());
        when(grants.saveAndFlush(any())).thenAnswer(store());

        ChatMemberRepository members = mock(ChatMemberRepository.class);
        ChatRepository chats = mock(ChatRepository.class);
        ContactRepository contacts = mock(ContactRepository.class);
        // One direct chat, one member each side: enough for a real grant to be issued.
        long chatId = 5L;
        Chat chat = new Chat();
        chat.setId(chatId);
        chat.setType(Chat.ChatType.direct);
        chat.setCreatedBy(7L);
        chat.setLastMessageAt(LocalDateTime.of(2026, 11, 20, 9, 31));
        when(chats.findById(chatId)).thenReturn(Optional.of(chat));
        when(chats.findDirectChatBetweenUsers(any(), any())).thenReturn(Optional.of(chat));
        when(members.existsByChatIdAndUserId(chatId, 7L)).thenReturn(true);
        when(members.existsByChatIdAndUserId(chatId, 9L)).thenReturn(true);
        when(members.findByChatId(chatId)).thenReturn(List.of(member(chatId, 7L), member(chatId, 9L)));
        when(contacts.existsByUserIdAndContactUserId(7L, 9L)).thenReturn(true);
        when(contacts.existsByUserIdAndContactUserId(9L, 7L)).thenReturn(true);

        this.service = new AgentScopeService(bindings, grants, members, chats, contacts, json);
    }

    private Answer<AgentScopeGrant> store() {
        return inv -> {
            AgentScopeGrant g = inv.getArgument(0);
            grantsById.put(g.grantId, g);
            return g;
        };
    }

    private static ChatMember member(long chatId, long userId) {
        ChatMember member = new ChatMember();
        member.setChatId(chatId);
        member.setUserId(userId);
        return member;
    }

    public AgentScopeService service() {
        return service;
    }

    /** Owner session with an explicit contact scope, then a real grant for this run. */
    public ScopeDtos.AuthorizedScope grantFor(Long actor, String session, String run, Long contactUserId) {
        var selection = new ScopeDtos.Selection(
                List.of(new ScopeDtos.Target("contact", contactUserId)), ScopeDtos.TimeRange.unbounded());
        if (!bindingsById.containsKey(session)) service.create(actor, session, selection);
        return service.authorize(actor, session, run, selection, true, 1L, null, false);
    }

    /** A binding exists but no grant was issued: the run never gained chat access. */
    public void bindOnly(Long actor, String session) {
        if (!bindingsById.containsKey(session)) {
            service.create(actor, session, new ScopeDtos.Selection(List.of(), ScopeDtos.TimeRange.unbounded()));
        }
    }
}
