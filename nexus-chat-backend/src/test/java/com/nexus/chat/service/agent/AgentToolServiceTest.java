package com.nexus.chat.service.agent;

import com.nexus.chat.agent.AgentToolFixture;
import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.model.Chat;
import com.nexus.chat.service.agent.tool.AgentToolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static com.nexus.chat.agent.AgentToolFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentToolServiceTest {
    private final AgentToolFixture fixture = AgentToolFixture.create();
    private final AgentToolService tools = fixture.service();

    private static void assertForbidden(Executable call) {
        assertEquals(AgentErrorCode.AGENT_AUTHZ_40301, assertThrows(AgentException.class, call).getCode());
    }

    private static void assertToolError(AgentToolException.Code code, Executable call) {
        assertEquals(code, assertThrows(AgentToolException.class, call).getCode());
    }

    private int pageSizeAskedFor() {
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(fixture.messages(), atLeastOnce()).findByChatIdOrderByCreatedAtDesc(eq(CHAT), page.capture());
        return page.getValue().getPageSize();
    }

    @Test
    void aMemberReadsTheChatNewestFirst() {
        Map<String, Object> body = tools.getRecentMessages(ALICE, CHAT, null);

        assertEquals(CHAT, body.get("chatId"));
        List<?> messages = (List<?>) body.get("messages");
        assertEquals(2, messages.size());
        Map<String, Object> expected = new java.util.LinkedHashMap<>();
        expected.put("messageId", SECOND_MESSAGE);
        expected.put("senderId", BOB);
        expected.put("senderName", "bob");
        expected.put("content", "hello alice");
        expected.put("createdAt", "2026-11-20T09:31");
        assertEquals(expected, messages.get(0));
        assertEquals(80, pageSizeAskedFor(), "default limit");
    }

    @Test
    void theLimitIsCappedAndMustBePositive() {
        tools.getRecentMessages(ALICE, CHAT, 5000);
        assertEquals(200, pageSizeAskedFor());
        assertEquals(1, ((List<?>) tools.getRecentMessages(ALICE, CHAT, 1).get("messages")).size());
        assertToolError(AgentToolException.Code.BAD_ARG, () -> tools.getRecentMessages(ALICE, CHAT, 0));
    }

    @Test
    void aNonMemberIsRefusedBeforeAnythingIsRead() {
        assertForbidden(() -> tools.getRecentMessages(CAROL, CHAT, null));
        assertForbidden(() -> tools.getChatProfile(CAROL, CHAT));
        assertForbidden(() -> tools.getMessageById(CAROL, FIRST_MESSAGE));

        verify(fixture.messages(), never()).findByChatIdOrderByCreatedAtDesc(anyLong(), any(Pageable.class));
        verify(fixture.chats(), never()).findById(anyLong());
        verify(fixture.members(), never()).findByChatId(anyLong());
    }

    @Test
    void anActorWhoIsNotAUserIsRefused() {
        assertForbidden(() -> tools.getUserProfile(404L, BOB));
        assertForbidden(() -> tools.findUserByUsername(404L, "bob"));
        assertForbidden(() -> tools.listMyChats(404L, null, null, null));
        verify(fixture.users(), never()).findByUsername(any());
        verify(fixture.chats(), never()).findByUserIdOrderByLastMessageAtDesc(anyLong());
    }

    @Test
    void chatAndMessageLookupsAnswerForMembers() {
        Map<String, Object> chat = tools.getChatProfile(BOB, CHAT);
        assertEquals("direct", chat.get("chatType"));
        assertEquals("", chat.get("chatName"));
        assertEquals(List.of(ALICE, BOB), chat.get("memberIds"));

        Map<String, Object> message = tools.getMessageById(BOB, FIRST_MESSAGE);
        assertEquals(CHAT, message.get("chatId"));
        assertEquals("alice", message.get("senderName"));
        assertEquals("hi bob", message.get("content"));
    }

    @Test
    void missingThingsAreNotFoundNotBadArguments() {
        when(fixture.members().existsByChatIdAndUserId(6L, ALICE)).thenReturn(true);
        assertToolError(AgentToolException.Code.NOT_FOUND, () -> tools.getChatProfile(ALICE, 6L));
        assertToolError(AgentToolException.Code.NOT_FOUND, () -> tools.getUserProfile(ALICE, 404L));
        assertToolError(AgentToolException.Code.NOT_FOUND, () -> tools.getMessageById(ALICE, 999L));
        assertToolError(AgentToolException.Code.NOT_FOUND, () -> tools.findUserByUsername(ALICE, "ghost"));
        assertToolError(AgentToolException.Code.NOT_FOUND, () -> tools.findDirectChatWithUser(ALICE, "ghost"));
        assertToolError(AgentToolException.Code.NOT_FOUND, () -> tools.findDirectChatWithUser(ALICE, "carol"));
    }

    @Test
    void handlesMayCarryALeadingAtSign() {
        Map<String, Object> profile = tools.findUserByUsername(ALICE, "  @bob ");
        assertEquals(BOB, profile.get("userId"));
        assertEquals("bob", profile.get("username"));
        assertEquals(profile, tools.findUserByUsername(ALICE, "bob"));
        assertEquals(profile, tools.getUserProfile(ALICE, BOB));

        Map<String, Object> direct = tools.findDirectChatWithUser(ALICE, "@bob");
        assertEquals(CHAT, direct.get("chatId"));
        assertEquals("direct", direct.get("type"));
        assertEquals(BOB, direct.get("otherUserId"));
        assertEquals("bob", direct.get("otherUsername"));

        assertToolError(AgentToolException.Code.BAD_ARG, () -> tools.findUserByUsername(ALICE, " @ "));
        assertToolError(AgentToolException.Code.BAD_ARG, () -> tools.findDirectChatWithUser(ALICE, ""));
    }

    @Test
    void myChatsAreOnlyTheActorsAndNamedAfterTheOtherMember() {
        List<?> alice = (List<?>) tools.listMyChats(ALICE, null, null, null).get("chats");
        assertEquals(1, alice.size());
        Map<?, ?> row = (Map<?, ?>) alice.get(0);
        assertEquals(CHAT, row.get("chatId"));
        assertEquals("direct", row.get("type"));
        assertEquals("@bob", row.get("name"));
        assertEquals("2026-11-20T09:31", row.get("lastMessageAt"));
        assertEquals("@alice", ((Map<?, ?>) ((List<?>) tools.listMyChats(BOB, null, null, null).get("chats")).get(0)).get("name"));

        assertEquals(List.of(), tools.listMyChats(CAROL, null, null, null).get("chats"));
        assertEquals(1, ((List<?>) tools.listMyChats(ALICE, " BOB ", "direct", null).get("chats")).size());
        assertEquals(List.of(), tools.listMyChats(ALICE, "nobody", null, null).get("chats"));
        assertEquals(List.of(), tools.listMyChats(ALICE, null, "group", null).get("chats"));
    }

    @Test
    void myChatsLimitDefaultsToTwentyAndStaysWithinOneToFifty() {
        when(fixture.chats().findByUserIdOrderByLastMessageAtDesc(ALICE)).thenReturn(LongStream.range(0, 60).mapToObj(i -> {
            Chat chat = new Chat();
            chat.setId(1000 + i);
            chat.setType(Chat.ChatType.group);
            chat.setName("group " + i);
            return chat;
        }).toList());

        assertEquals(20, ((List<?>) tools.listMyChats(ALICE, null, null, null).get("chats")).size());
        assertEquals(50, ((List<?>) tools.listMyChats(ALICE, null, null, 500).get("chats")).size());
        assertEquals(1, ((List<?>) tools.listMyChats(ALICE, null, null, 0).get("chats")).size());
        assertEquals(7, ((List<?>) tools.listMyChats(ALICE, null, "group", 7).get("chats")).size());
    }
}
