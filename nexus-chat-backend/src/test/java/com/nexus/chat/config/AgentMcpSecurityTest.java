package com.nexus.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.agent.AgentToolFixture;
import com.nexus.chat.controller.agent.InternalAgentController;
import com.nexus.chat.exception.agent.AgentExceptionHandler;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.repository.MessageRepository;
import com.nexus.chat.repository.UserSessionRepository;
import com.nexus.chat.security.JwtAuthenticationFilter;
import com.nexus.chat.security.JwtTokenProvider;
import com.nexus.chat.service.agent.AgentToolService;
import com.nexus.chat.service.agent.tool.AgentToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The MCP servlet behind a real Tomcat and the application's real
 * {@link SecurityConfig}: two servlets side by side, the JWT filter in the
 * chain, no database. Only the repositories are stand-ins.
 */
@SpringBootTest(classes = AgentMcpSecurityTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "agent.internal.token=fixture-internal-token"})
class AgentMcpSecurityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

    @Configuration
    @ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class,
            ErrorMvcAutoConfiguration.class, SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
    @Import({SecurityConfig.class, JwtAuthenticationFilter.class, AgentMcpConfig.class, AgentToolRegistry.class,
            InternalAgentController.class, AgentExceptionHandler.class})
    static class App {
        private final AgentToolFixture fixture = AgentToolFixture.create();

        @Bean
        AgentToolService agentToolService() {
            return fixture.service();
        }

        @Bean
        ChatMemberRepository chatMemberRepository() {
            return fixture.members();
        }

        @Bean
        MessageRepository messageRepository() {
            return fixture.messages();
        }

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return mock(JwtTokenProvider.class);
        }

        @Bean
        UserSessionRepository userSessionRepository() {
            return mock(UserSessionRepository.class);
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    MessageRepository messages;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void withoutTheInternalTokenTheAnswerIs401AndNotSpringSecuritys403() throws Exception {
        for (String[] headers : List.of(new String[0], new String[]{"Authorization", "Bearer wrong"},
                new String[]{"Authorization", "Bearer wrong", "X-Actor-User-Id", "7"})) {
            HttpResponse<String> response = send("POST", AgentMcpConfig.MCP_PATH, LIST, headers);
            assertEquals(401, response.statusCode(), response.body());
            assertEquals("AGENT_AUTH_40101", JSON.readTree(response.body()).path("code").asText());
            assertFalse(response.body().contains("tools"));
        }
        assertEquals(401, send("GET", AgentMcpConfig.MCP_PATH, null).statusCode());
    }

    @Test
    void withTheTokenToolsAreListedAndCalledAsTheHeaderActor() throws Exception {
        HttpResponse<String> list = send("POST", AgentMcpConfig.MCP_PATH, LIST, "Authorization", "Bearer fixture-internal-token");
        assertEquals(200, list.statusCode(), list.body());
        List<String> names = new ArrayList<>();
        JSON.readTree(list.body()).at("/result/tools").forEach(tool -> names.add(tool.path("name").asText()));
        assertEquals(7, names.size(), names.toString());

        String call = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"get_recent_messages\",\"arguments\":{\"chat_id\":5}}}";
        JsonNode member = JSON.readTree(send("POST", AgentMcpConfig.MCP_PATH, call,
                "Authorization", "Bearer fixture-internal-token", "X-Actor-User-Id", "7").body()).path("result");
        assertEquals(2, member.at("/structuredContent/messages").size(), member.toString());

        JsonNode stranger = JSON.readTree(send("POST", AgentMcpConfig.MCP_PATH, call,
                "Authorization", "Bearer fixture-internal-token", "X-Actor-User-Id", "8").body()).path("result");
        assertTrue(stranger.path("isError").asBoolean(), stranger.toString());
        assertEquals("FORBIDDEN", JSON.readTree(stranger.at("/content/0/text").asText()).path("toolError").asText());
    }

    @Test
    void theOldReadEndpointsAreGoneAndPublishStillChecksTheToken() throws Exception {
        for (String path : List.of("/internal/agent/chats/5/recent-messages", "/internal/agent/chats/5/profile",
                "/internal/agent/users/9/profile", "/internal/agent/users/by-username/bob/profile",
                "/internal/agent/messages/101", "/internal/agent/me/chats", "/internal/agent/me/chats/with-user/bob")) {
            assertEquals(404, send("GET", path, null, "Authorization", "Bearer fixture-internal-token",
                    "X-Actor-User-Id", "7").statusCode(), path);
        }

        String publish = "{\"chatId\":5,\"senderUserId\":7,\"content\":\"hi\"}";
        HttpResponse<String> refused = send("POST", "/internal/agent/messages/publish", publish,
                "Authorization", "Bearer wrong", "X-Actor-User-Id", "7");
        assertEquals(401, refused.statusCode(), refused.body());
        assertEquals("AGENT_AUTH_40101", JSON.readTree(refused.body()).path("code").asText());
        verify(messages, never()).save(any());
    }

    @Test
    void everythingOutsideTheInternalPrefixStillNeedsAUser() throws Exception {
        int status = send("GET", "/api/chats", null, "Authorization", "Bearer fixture-internal-token").statusCode();
        assertTrue(status == 401 || status == 403, "got " + status);
    }
}
