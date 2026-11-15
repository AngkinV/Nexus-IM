package com.nexus.chat.controller;

import com.nexus.chat.dto.ChatDTO;
import com.nexus.chat.exception.GlobalExceptionHandler;
import com.nexus.chat.service.ChatService;
import com.nexus.chat.service.ContactService;
import com.nexus.chat.service.MessageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc over the real controllers and exception handler. The JWT filter is not
 * in the chain, so the principal it would set is placed in the SecurityContext directly.
 */
class ActingUserMvcTest {

    private final ChatService chatService = mock(ChatService.class);
    private final ContactService contactService = mock(ContactService.class);
    private final MessageService messageService = mock(MessageService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new ChatController(chatService),
                        new ContactController(contactService),
                        new MessageController(messageService, null, null, null, null))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList()));
    }

    @Test
    void requestParamMatchingTokenPasses() throws Exception {
        loginAs(1L);

        mvc.perform(get("/api/chats/5").param("userId", "1")).andExpect(status().isOk());

        verify(chatService).getChatById(5L, 1L);
    }

    @Test
    void requestParamMayBeOmitted() throws Exception {
        loginAs(1L);

        mvc.perform(get("/api/chats/5")).andExpect(status().isOk());

        verify(chatService).getChatById(5L, 1L);
    }

    @Test
    void requestParamNamingAnotherUserIsForbidden() throws Exception {
        loginAs(1L);

        mvc.perform(get("/api/chats/5").param("userId", "2")).andExpect(status().isForbidden());

        verifyNoInteractions(chatService);
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/chats/5").param("userId", "1")).andExpect(status().isUnauthorized());

        verifyNoInteractions(chatService);
    }

    @Test
    void pathVariableNamingAnotherUserIsForbidden() throws Exception {
        loginAs(1L);

        mvc.perform(get("/api/chats/user/1")).andExpect(status().isOk());
        mvc.perform(get("/api/chats/user/2")).andExpect(status().isForbidden());
        mvc.perform(get("/api/contacts/blacklist/2")).andExpect(status().isForbidden());

        verify(chatService).getUserChats(1L);
        verifyNoInteractions(contactService);
    }

    @Test
    void targetParameterIsNotTreatedAsIdentity() throws Exception {
        loginAs(1L);
        when(chatService.createDirectChat(1L, 2L)).thenReturn(new ChatDTO());

        mvc.perform(post("/api/chats/direct").param("userId", "1").param("contactId", "2"))
                .andExpect(status().isOk());

        verify(chatService).createDirectChat(1L, 2L);
    }

    @Test
    void bodyFieldMatchingTokenPasses() throws Exception {
        loginAs(1L);

        mvc.perform(delete("/api/contacts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"contactUserId\":2}"))
                .andExpect(status().isOk());

        verify(contactService).removeContact(1L, 2L);
    }

    // These endpoints wrap their body in catch (RuntimeException) -> 400; the 403 must not be swallowed.
    @Test
    void bodyFieldNamingAnotherUserIsForbidden() throws Exception {
        loginAs(1L);

        mvc.perform(post("/api/contacts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":2,\"contactUserId\":3}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":5,\"senderId\":2,\"content\":\"hi\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(contactService, messageService);
    }

    @Test
    void messageHistoryUsesTokenUser() throws Exception {
        loginAs(1L);

        mvc.perform(get("/api/messages/chat/5").param("userId", "1")).andExpect(status().isOk());
        mvc.perform(get("/api/messages/chat/5").param("userId", "2")).andExpect(status().isForbidden());
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/messages/chat/5").param("userId", "1")).andExpect(status().isUnauthorized());

        verify(messageService).getChatMessages(eq(5L), eq(1L), anyInt(), anyInt());
        verify(messageService, never()).getChatMessages(any(), eq(2L), anyInt(), anyInt());
    }
}
