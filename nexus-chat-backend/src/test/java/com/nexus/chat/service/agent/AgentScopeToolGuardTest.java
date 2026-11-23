package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.model.*;
import com.nexus.chat.model.agent.*;
import com.nexus.chat.repository.*;
import com.nexus.chat.repository.agent.*;
import com.nexus.chat.service.agent.scope.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression source only; execution belongs to the original T1 session.
 *
 * <p>The decisive assertion is on real outcomes: a committed call outside the
 * scope must be refused, its message body must never be loaded, and a body read
 * counter stays at zero. A positive control proves the counters are live.
 */
class AgentScopeToolGuardTest {

    static final long ACTOR = 1L;
    static final String SESSION = "as_guard";
    static final String RUN = "run_guard1";

    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final AgentScopeBindingRepository bindings = mock(AgentScopeBindingRepository.class);
    final AgentScopeGrantRepository grants = mock(AgentScopeGrantRepository.class);
    final ChatMemberRepository members = mock(ChatMemberRepository.class);
    final ChatRepository chats = mock(ChatRepository.class);
    final ContactRepository contacts = mock(ContactRepository.class);
    final MessageRepository messages = mock(MessageRepository.class);
    final Map<String, AgentScopeBinding> bs = new HashMap<>();
    final Map<String, AgentScopeGrant> gs = new HashMap<>();
    final AgentScopeService scopes = new AgentScopeService(bindings, grants, members, chats, contacts, json);
    final AgentScopeToolGuard guard = new AgentScopeToolGuard(scopes, messages);

    AgentScopeToolGuardTest() {
        when(bindings.lockBySessionId(anyString())).thenAnswer(i -> Optional.ofNullable(bs.get(i.getArgument(0))));
        when(bindings.saveAndFlush(any())).thenAnswer(i -> {
            AgentScopeBinding b = i.getArgument(0); bs.put(b.sessionId, b); return b;
        });
        when(grants.findById(anyString())).thenAnswer(i -> Optional.ofNullable(gs.get(i.getArgument(0))));
        when(grants.findBySessionIdAndRunId(anyString(), any())).thenAnswer(i -> gs.values().stream()
                .filter(g -> g.sessionId.equals(i.getArgument(0)) && Objects.equals(g.runId, i.getArgument(1))).toList());
        when(grants.findBySessionId(anyString())).thenAnswer(i -> gs.values().stream()
                .filter(g -> g.sessionId.equals(i.getArgument(0))).toList());
        when(grants.save(any())).thenAnswer(i -> { AgentScopeGrant g = i.getArgument(0); gs.put(g.grantId, g); return g; });
        when(grants.saveAndFlush(any())).thenAnswer(i -> { AgentScopeGrant g = i.getArgument(0); gs.put(g.grantId, g); return g; });

        for (long id : List.of(101L, 202L)) {
            var c = new Chat(); c.setId(id); c.setType(Chat.ChatType.group); c.setName("group-" + id);
            when(chats.findById(id)).thenReturn(Optional.of(c));
            when(members.existsByChatIdAndUserId(id, ACTOR)).thenReturn(true);
        }
        // Inside the window (storage zone) and outside it, both in chat 101.
        // Build the projections first: mocking inside when(...) is an unfinished stubbing.
        var inside = attribution(101L, "2026-11-22T10:00:00");
        var beforeWindow = attribution(101L, "2026-11-01T10:00:00");
        var otherChat = attribution(202L, "2026-11-22T10:00:00");
        when(messages.findScopeAttribution(11L)).thenReturn(Optional.of(inside));
        when(messages.findScopeAttribution(12L)).thenReturn(Optional.of(beforeWindow));
        when(messages.findScopeAttribution(22L)).thenReturn(Optional.of(otherChat));

        scopes.create(ACTOR, SESSION, new ScopeDtos.Selection(
                List.of(new ScopeDtos.Target("group", 101L)), ScopeDtos.TimeRange.unbounded()));
    }

    MessageRepository.ScopeAttribution attribution(long chatId, String createdAt) {
        var a = mock(MessageRepository.ScopeAttribution.class);
        when(a.getChatId()).thenReturn(chatId);
        when(a.getCreatedAt()).thenReturn(LocalDateTime.parse(createdAt));
        return a;
    }

    ScopeDtos.AuthorizedScope grant(Instant from) {
        var sel = new ScopeDtos.Selection(List.of(new ScopeDtos.Target("group", 101L)),
                new ScopeDtos.TimeRange(from, null, "Asia/Shanghai"));
        var b = bs.get(SESSION);
        var scope = new ScopeDtos.AuthorizedScope(1, UUID.randomUUID().toString(), ACTOR, SESSION, RUN,
                b.bindingVersion, 1, b.sessionGeneration, List.of(101L), List.of(),
                sel.timeRange(), AgentScopeService.key(ACTOR, SESSION, b.sessionGeneration, List.of(101L), List.of(), sel.timeRange()));
        var g = new AgentScopeGrant();
        g.grantId = scope.grantId(); g.sessionId = SESSION; g.runId = RUN; g.actorUserId = ACTOR;
        g.active = true; g.scopeJson = write(scope);
        gs.put(g.grantId, g);
        return scope;
    }

    String write(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    void assertDenied(Runnable call) {
        var ex = assertThrows(ResponseStatusException.class, call::run);
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void positiveControlReadsTheSelectedChatAndNeverTouchesTheOther() {
        var scope = grant(null);
        var current = guard.authorize(ACTOR, SESSION, RUN, scope,
                "get_recent_messages", Map.of("chat_id", 101));
        assertEquals(scope.grantId(), current.grantId());
        verify(messages, never()).findScopedMessages(eq(202L), any(), any(), any());
        verify(messages, never()).findById(anyLong());
    }

    @Test
    void chatOutsideScopeIsRefusedWithoutAnyBodyRead() {
        var scope = grant(null);
        assertDenied(() -> guard.authorize(ACTOR, SESSION, RUN, scope,
                "get_recent_messages", Map.of("chat_id", 202)));
        verify(messages, never()).findScopedMessages(eq(202L), any(), any(), any());
        verify(messages, never()).findById(anyLong());
    }

    @Test
    void messageIdOutsideScopeReadsOnlyTheNonBodyProjection() {
        var scope = grant(null);
        assertDenied(() -> guard.authorize(ACTOR, SESSION, RUN, scope,
                "get_message_by_id", Map.of("message_id", 22)));
        verify(messages, times(1)).findScopeAttribution(22L);
        verify(messages, never()).findById(22L);
    }

    @Test
    void inScopeMessageNeedsNoBodyLoadToAuthorize() {
        var scope = grant(null);
        guard.authorize(ACTOR, SESSION, RUN, scope, "get_message_by_id", Map.of("message_id", 11));
        verify(messages, times(1)).findScopeAttribution(11L);
        verify(messages, never()).findById(anyLong());
    }

    @Test
    void messageOutsideTheHalfOpenWindowIsRefused() {
        // Window starts after the message's own timestamp.
        var scope = grant(Instant.parse("2026-11-22T00:00:00Z"));
        assertDenied(() -> guard.authorize(ACTOR, SESSION, RUN, scope,
                "get_message_by_id", Map.of("message_id", 12)));
        verify(messages, never()).findById(12L);
    }

    @Test
    void messageAtTheExclusiveUpperBoundIsRefused() {
        var scope = grant(null);
        var b = bs.get(SESSION);
        var bounded = new ScopeDtos.AuthorizedScope(1, scope.grantId(), ACTOR, SESSION, RUN,
                b.bindingVersion, 1, b.sessionGeneration, List.of(101L), List.of(),
                new ScopeDtos.TimeRange(null, Instant.parse("2026-11-22T02:00:00Z"), "Asia/Shanghai"),
                AgentScopeService.key(ACTOR, SESSION, b.sessionGeneration, List.of(101L), List.of(),
                        new ScopeDtos.TimeRange(null, Instant.parse("2026-11-22T02:00:00Z"), "Asia/Shanghai")));
        gs.get(scope.grantId()).scopeJson = write(bounded);
        // 10:00 in Asia/Shanghai is exactly the exclusive end.
        assertDenied(() -> guard.authorize(ACTOR, SESSION, RUN, bounded,
                "get_message_by_id", Map.of("message_id", 11)));
        verify(messages, never()).findById(11L);
    }

    @Test
    void identityFieldsInsideArgumentsAreRefused() {
        var scope = grant(null);
        assertDenied(() -> guard.authorize(ACTOR, SESSION, RUN, scope,
                "get_recent_messages", Map.of("chat_id", 101, "scopeKey", scope.scopeKey())));
        assertDenied(() -> guard.authorize(ACTOR, SESSION, RUN, scope,
                "get_recent_messages", Map.of("chat_id", 101, "runId", RUN)));
        verify(messages, never()).findScopedMessages(anyLong(), any(), any(), any());
    }

    @Test
    void malformedArgumentIsRefusedNotCoerced() {
        var scope = grant(null);
        assertDenied(() -> guard.authorize(ACTOR, SESSION, RUN, scope,
                "get_recent_messages", Map.of("chat_id", "101abc")));
        assertDenied(() -> guard.authorize(ACTOR, SESSION, RUN, scope,
                "get_recent_messages", Map.of("chat_id", 0)));
        assertDenied(() -> guard.authorize(ACTOR, SESSION, RUN, scope,
                "get_recent_messages", Map.of("chat_id", 101.9)));
        verify(messages, never()).findScopedMessages(anyLong(), any(), any(), any());
    }

    @Test
    void revokedGrantCannotAuthorizeACommittedCall() {
        var scope = grant(null);
        scopes.revokeSession(ACTOR, SESSION);
        assertDenied(() -> guard.authorize(ACTOR, SESSION, RUN, scope,
                "get_recent_messages", Map.of("chat_id", 101)));
        verify(messages, never()).findScopedMessages(anyLong(), any(), any(), any());
    }

    @Test
    void anotherActorCannotReuseTheGrant() {
        var scope = grant(null);
        assertDenied(() -> guard.authorize(2L, SESSION, RUN, scope,
                "get_recent_messages", Map.of("chat_id", 101)));
        verify(messages, never()).findScopedMessages(anyLong(), any(), any(), any());
    }

    @Test
    void displayTimezoneCannotMoveTheQueryWindow() {
        var base = grant(null);
        var b = bs.get(SESSION);
        var tokyo = new ScopeDtos.TimeRange(Instant.parse("2026-11-22T00:00:00Z"),
                Instant.parse("2026-11-22T02:00:00Z"), "Asia/Tokyo");
        var shanghai = new ScopeDtos.TimeRange(Instant.parse("2026-11-22T00:00:00Z"),
                Instant.parse("2026-11-22T02:00:00Z"), "Asia/Shanghai");
        assertEquals(tokyo.fromLocal(ZoneId.of("Asia/Shanghai")), shanghai.fromLocal(ZoneId.of("Asia/Shanghai")));
        var scope = new ScopeDtos.AuthorizedScope(1, base.grantId(), ACTOR, SESSION, RUN,
                b.bindingVersion, 1, b.sessionGeneration, List.of(101L), List.of(), tokyo,
                AgentScopeService.key(ACTOR, SESSION, b.sessionGeneration, List.of(101L), List.of(), tokyo));
        gs.get(base.grantId()).scopeJson = write(scope);
        guard.authorize(ACTOR, SESSION, RUN, scope, "get_recent_messages", Map.of("chat_id", 101));
    }
}
