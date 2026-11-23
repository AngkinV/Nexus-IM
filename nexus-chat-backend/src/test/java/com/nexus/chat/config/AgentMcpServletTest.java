package com.nexus.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.chat.agent.AgentToolFixture;
import com.nexus.chat.service.agent.tool.AgentToolRegistry;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.nexus.chat.agent.AgentToolFixture.CHAT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** JSON-RPC through the real MCP servlet and server, as wired by {@link AgentMcpConfig}. */
@ExtendWith(OutputCaptureExtension.class)
class AgentMcpServletTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentToolFixture fixture = AgentToolFixture.create();
    private final AgentMcpConfig config = new AgentMcpConfig();
    private final HttpServletStatelessServerTransport transport = config.agentMcpTransport();
    private final McpStatelessSyncServer server =
            config.agentMcpServer(transport, new AgentToolRegistry(List.of(fixture.service())));

    @AfterEach
    void close() {
        server.close();
    }

    private MockHttpServletResponse post(String body, Map<String, String> headers) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", AgentMcpConfig.MCP_PATH);
        request.setContentType("application/json");
        request.addHeader("Accept", "application/json, text/event-stream");
        headers.forEach(request::addHeader);
        try { var header=fixture.scopeHeader(Long.parseLong(headers.getOrDefault("X-Actor-User-Id",""))); if(header!=null&&!headers.containsKey("X-Agent-Scope")) request.addHeader("X-Agent-Scope",header); } catch(NumberFormatException ignored) { }
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        transport.service(request, response);
        return response;
    }

    private JsonNode rpc(String method, Object params, Map<String, String> headers) throws Exception {
        ObjectNode body = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", method);
        if (params != null) body.set("params", JSON.valueToTree(params));
        MockHttpServletResponse response = post(body.toString(), headers);
        assertEquals(200, response.getStatus(), response.getContentAsString(StandardCharsets.UTF_8));
        return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode call(String tool, Map<String, Object> arguments, String actor) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", tool);
        params.put("arguments", arguments);
        JsonNode response = rpc("tools/call", params, actor == null ? Map.of() : Map.of("X-Actor-User-Id", actor));
        assertTrue(response.path("error").isMissingNode(), "tool failures must not be protocol errors: " + response);
        return response.path("result");
    }

    private static JsonNode toolError(JsonNode result) throws Exception {
        assertTrue(result.path("isError").asBoolean(), result.toString());
        return JSON.readTree(result.at("/content/0/text").asText());
    }

    @Test
    void initializeAndListExposeExactlyTheSevenReadOnlyTools() throws Exception {
        JsonNode init = rpc("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "test", "version", "1")), Map.of());
        assertEquals("nexus-agent-tools", init.at("/result/serverInfo/name").asText());
        assertFalse(init.at("/result/capabilities/tools").isMissingNode());

        List<String> names = new ArrayList<>();
        rpc("tools/list", null, Map.of()).at("/result/tools").forEach(tool -> names.add(tool.path("name").asText()));
        assertEquals(List.of("find_direct_chat_with_user", "find_user_by_username", "get_chat_profile",
                "get_message_by_id", "get_recent_messages", "get_user_profile", "list_my_chats"), names);
        assertFalse(names.contains("publish"), "writes are not tools");
    }

    @Test
    void aCallAnswersWithStructuredContentAndTheSameJsonAsText(CapturedOutput output) throws Exception {
        Map<String, Object> params = Map.of("name", "get_recent_messages", "arguments", Map.of("chat_id", 5));
        JsonNode result = rpc("tools/call", params, Map.of("X-Actor-User-Id", "7", "X-Trace-Id", "trace-42",
                "traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")).path("result");

        assertFalse(result.path("isError").asBoolean());
        JsonNode payload = result.path("structuredContent");
        assertEquals(5, payload.path("chatId").intValue());
        assertEquals(2, payload.path("messages").size());
        assertEquals("hello alice", payload.at("/messages/0/content").asText());
        assertEquals("bob", payload.at("/messages/0/senderName").asText());
        assertEquals(payload, JSON.readTree(result.at("/content/0/text").asText()));

        String line = output.getAll().lines().filter(l -> l.contains("agent tool call:")).findFirst().orElseThrow();
        assertTrue(line.contains("tool=get_recent_messages actor=7 traceId=trace-42 "
                + "traceparent=00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01 tookMs="), line);
        assertTrue(line.endsWith("result=OK"), line);
        assertFalse(line.contains("hello alice"), "chat content does not belong in the log line");
    }

    @Test
    void identityIsTheHeaderAndNothingElse() throws Exception {
        assertEquals(1, call("list_my_chats", Map.of(), "7").at("/structuredContent/chats").size());
        assertEquals("@bob", call("list_my_chats", Map.of(), "7").at("/structuredContent/chats/0/name").asText());
        assertEquals("@alice", call("list_my_chats", Map.of(), "9").at("/structuredContent/chats/0/name").asText());
        assertEquals(0, call("list_my_chats", Map.of(), "8").at("/structuredContent/chats").size());
    }

    @Test
    void aNonMemberIsForbidden() throws Exception {
        JsonNode error = toolError(call("get_recent_messages", Map.of("chat_id", 5), "8"));
        assertEquals("FORBIDDEN", error.path("toolError").asText());
        assertEquals("FORBIDDEN", toolError(call("get_chat_profile", Map.of("chat_id", 5), "8")).path("toolError").asText());
        assertEquals("FORBIDDEN", toolError(call("get_message_by_id", Map.of("message_id", 101), "8")).path("toolError").asText());
        verify(fixture.messages(), never()).findByChatIdOrderByCreatedAtDesc(anyLong(), any(Pageable.class));
    }

    @Test
    void anActorSmuggledIntoTheArgumentsNeverChangesWhoIsAsking() throws Exception {
        // Carol (8) is not in chat 5; alice (7) is. Carol's request claims to be alice in every way a model could.
        for (String forged : List.of("actor", "actorUserId", "actor_user_id", "X-Actor-User-Id", "user_id")) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("chat_id", 5);
            arguments.put(forged, 7);
            JsonNode result = call("get_recent_messages", arguments, "8");
            // Refused either by the schema (BAD_ARG) or, before any schema check, by the
            // scope authority. What must never happen is a read performed as alice.
            String code = toolError(result).path("toolError").asText();
            assertTrue(List.of("BAD_ARG", "FORBIDDEN").contains(code), forged + " -> " + code);
            assertTrue(result.path("structuredContent").isMissingNode() || result.path("structuredContent").isNull());
        }

        // With a legitimate grant, the smuggled identity field is rejected as a bad argument.
        JsonNode withScope = rpc("tools/call", Map.of("name", "get_recent_messages",
                "arguments", Map.of("chat_id", 5, "actorUserId", 7)),
                Map.of("X-Actor-User-Id", "7", "X-Agent-Scope", fixture.scopeHeader(7L))).path("result");
        assertEquals("BAD_ARG", toolError(withScope).path("toolError").asText());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "get_recent_messages");
        params.put("arguments", Map.of("chat_id", 5));
        params.put("_meta", Map.of("actorUserId", 7, "X-Actor-User-Id", "7"));
        JsonNode viaMeta = rpc("tools/call", params, Map.of("X-Actor-User-Id", "8")).path("result");
        assertEquals("FORBIDDEN", toolError(viaMeta).path("toolError").asText());

        verify(fixture.messages(), never()).findByChatIdOrderByCreatedAtDesc(anyLong(), any(Pageable.class));
        verify(fixture.members(), never()).existsByChatIdAndUserId(CHAT, 7L);
    }

    @Test
    void withoutAUsableActorHeaderNothingIsQueried() throws Exception {
        assertEquals("NO_ACTOR", toolError(call("get_recent_messages", Map.of("chat_id", 5), null)).path("toolError").asText());
        assertEquals("NO_ACTOR", toolError(call("list_my_chats", Map.of(), "alice")).path("toolError").asText());
        assertEquals("NO_ACTOR", toolError(call("get_user_profile", Map.of("user_id", 9), "0")).path("toolError").asText());
        assertEquals("NO_ACTOR", toolError(call("get_user_profile", Map.of("user_id", 9), "-7")).path("toolError").asText());
        verifyNoInteractions(fixture.members(), fixture.messages(), fixture.chats(), fixture.users());
    }

    @Test
    void notFoundAndBadArgumentsAreToolErrorsNotProtocolErrors() throws Exception {
        assertEquals("FORBIDDEN", toolError(call("get_user_profile", Map.of("user_id", 404), "7")).path("toolError").asText());
        assertEquals("NOT_FOUND", toolError(call("find_direct_chat_with_user", Map.of("username", "carol"), "7")).path("toolError").asText());
        assertEquals("BAD_ARG", toolError(call("find_user_by_username", Map.of("username", "@"), "7")).path("toolError").asText());
        assertEquals("BAD_ARG", toolError(call("get_recent_messages", Map.of("chat_id", "5"), "7")).path("toolError").asText());
        assertEquals("BAD_ARG", toolError(call("get_recent_messages", Map.of(), "7")).path("toolError").asText());
        assertEquals("BAD_ARG", toolError(call("list_my_chats", Map.of("type", "channel"), "7")).path("toolError").asText());
        assertEquals(9, call("find_user_by_username", Map.of("username", "@bob"), "7").at("/structuredContent/userId").intValue());
    }

    @Test
    void anUnknownToolIsAProtocolError() throws Exception {
        JsonNode response = rpc("tools/call", Map.of("name", "publish", "arguments", Map.of()), Map.of("X-Actor-User-Id", "7"));
        assertFalse(response.path("error").isMissingNode(), response.toString());
        assertTrue(response.path("result").isMissingNode());
    }

    @Test
    void theTransportInsistsOnJsonAndTheStreamableAcceptHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", AgentMcpConfig.MCP_PATH);
        request.setContentType("application/json");
        request.setContent("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        transport.service(request, response);
        assertEquals(400, response.getStatus());
    }

    @Test
    void aRejectedRequestIsAnsweredWithoutAStackTrace() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", AgentMcpConfig.MCP_PATH);
        request.setContentType("text/plain");
        request.addHeader("Accept", "application/json, text/event-stream");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        transport.service(request, response);
        assertEquals(415, response.getStatus());
        JsonNode body = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertTrue(body.path("message").asText().contains("Content-Type"), body.toString());
        assertTrue(body.path("stackTrace").isMissingNode(), "no stack trace in a response body");
        assertTrue(body.path("cause").isMissingNode());
    }
}
