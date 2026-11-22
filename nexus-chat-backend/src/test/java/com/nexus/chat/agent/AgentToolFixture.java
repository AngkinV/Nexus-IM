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

    public AgentToolService service() {
        return new AgentToolService(members, messages, chats, users);
    }

    public static AgentToolFixture create() {
        AgentToolFixture fixture = new AgentToolFixture(mock(ChatMemberRepository.class),
                mock(MessageRepository.class), mock(ChatRepository.class), mock(UserRepository.class));

        for (User user : List.of(user(ALICE, "alice"), user(CAROL, "carol"), user(BOB, "bob"))) {
            when(fixture.users.findById(user.getId())).thenReturn(Optional.of(user));
            when(fixture.users.existsById(user.getId())).thenReturn(true);
            when(fixture.users.findByUsername(user.getUsername())).thenReturn(Optional.of(user));
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
