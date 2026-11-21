package com.nexus.chat.service.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.agent.AgentToolFixture;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentToolRegistryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A second provider: one annotated method is the whole cost of a new tool. */
    public static class SampleTools implements AgentToolProvider {
        final AtomicInteger calls = new AtomicInteger();

        @AgentTool(name = "echo_sample", description = "Returns its arguments.")
        public Map<String, Object> echo(@Actor long actor,
                                        @ToolParam(name = "text", description = "Anything") String text,
                                        @ToolParam(name = "times", required = false) Integer times,
                                        @ToolParam(name = "big", required = false) Long big) {
            calls.incrementAndGet();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("actor", actor);
            out.put("text", text);
            out.put("times", times);
            out.put("big", big);
            return out;
        }

        @AgentTool(name = "boom_sample", description = "Always fails.")
        public Map<String, Object> boom(@Actor Long actor) {
            calls.incrementAndGet();
            throw new IllegalStateException("jdbc:mysql://db.internal:3306 password=hunter2");
        }

        public Map<String, Object> notATool(String ignored) {
            return Map.of();
        }
    }

    private final SampleTools sample = new SampleTools();
    private final AgentToolRegistry registry = new AgentToolRegistry(List.of(AgentToolFixture.create().service(), sample));

    private SyncToolSpecification spec(String name) {
        return registry.specifications().stream().filter(s -> s.tool().name().equals(name)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> properties(String tool) {
        return (Map<String, Map<String, Object>>) spec(tool).tool().inputSchema().get("properties");
    }

    private McpSchema.CallToolResult call(String tool, Map<String, Object> context, Map<String, Object> arguments) {
        return spec(tool).callHandler().apply(McpTransportContext.create(context),
                new McpSchema.CallToolRequest(tool, arguments, null));
    }

    private static JsonNode text(McpSchema.CallToolResult result) throws Exception {
        assertEquals(1, result.content().size());
        return JSON.readTree(((McpSchema.TextContent) result.content().get(0)).text());
    }

    private static void assertToolError(String code, McpSchema.CallToolResult result) throws Exception {
        assertEquals(Boolean.TRUE, result.isError());
        assertNull(result.structuredContent());
        assertEquals(code, text(result).path("toolError").asText());
        assertFalse(text(result).path("message").asText().isBlank());
    }

    private void assertSchema(String tool, Map<String, String> types, List<String> required) {
        Map<String, Object> schema = spec(tool).tool().inputSchema();
        assertEquals("object", schema.get("type"), tool);
        assertEquals(false, schema.get("additionalProperties"), tool);
        assertEquals(required, schema.get("required"), tool);
        Map<String, String> actual = new LinkedHashMap<>();
        properties(tool).forEach((name, property) -> actual.put(name, (String) property.get("type")));
        assertEquals(types, actual, tool + ": exactly these arguments, and no actor among them");
        assertFalse(spec(tool).tool().description().isBlank(), tool);
    }

    @Test
    void theSevenChatToolsHaveTheAgreedNamesAndArguments() {
        assertEquals(List.of("boom_sample", "echo_sample", "find_direct_chat_with_user", "find_user_by_username",
                        "get_chat_profile", "get_message_by_id", "get_recent_messages", "get_user_profile", "list_my_chats"),
                registry.specifications().stream().map(s -> s.tool().name()).toList());

        Map<String, String> chatAndLimit = new LinkedHashMap<>();
        chatAndLimit.put("chat_id", "integer");
        chatAndLimit.put("limit", "integer");
        assertSchema("get_recent_messages", chatAndLimit, List.of("chat_id"));
        assertSchema("get_chat_profile", Map.of("chat_id", "integer"), List.of("chat_id"));
        assertSchema("get_user_profile", Map.of("user_id", "integer"), List.of("user_id"));
        assertSchema("get_message_by_id", Map.of("message_id", "integer"), List.of("message_id"));
        assertSchema("find_user_by_username", Map.of("username", "string"), List.of("username"));
        assertSchema("find_direct_chat_with_user", Map.of("username", "string"), List.of("username"));
        Map<String, String> listing = new LinkedHashMap<>();
        listing.put("query", "string");
        listing.put("type", "string");
        listing.put("limit", "integer");
        assertSchema("list_my_chats", listing, List.of());

        assertEquals(List.of("direct", "group"), properties("list_my_chats").get("type").get("enum"));
        assertFalse(properties("list_my_chats").get("query").containsKey("enum"));
        assertEquals("Chat ID to read", properties("get_recent_messages").get("chat_id").get("description"));
    }

    @Test
    void noSchemaMentionsTheActor() {
        for (SyncToolSpecification spec : registry.specifications()) {
            String schema = JSON.valueToTree(spec.tool().inputSchema()).toString().toLowerCase();
            assertFalse(schema.contains("actor"), spec.tool().name() + ": " + schema);
        }
    }

    @Test
    void anAnnotatedMethodOnAnyProviderBecomesACallableTool() throws Exception {
        Map<String, String> echo = new LinkedHashMap<>();
        echo.put("text", "string");
        echo.put("times", "integer");
        echo.put("big", "integer");
        assertSchema("echo_sample", echo, List.of("text"));

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("text", "hi");
        arguments.put("big", 9_000_000_000L);
        McpSchema.CallToolResult result = call("echo_sample", Map.of(AgentToolRegistry.CTX_ACTOR, "7"), arguments);

        assertNotEquals(Boolean.TRUE, result.isError());
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("actor", 7L);
        expected.put("text", "hi");
        expected.put("times", null);
        expected.put("big", 9_000_000_000L);
        assertEquals(expected, result.structuredContent());
        assertEquals(JSON.writeValueAsString(expected), text(result).toString(), "the text block is the same payload as JSON");

        McpSchema.CallToolResult ints = call("echo_sample", Map.of(AgentToolRegistry.CTX_ACTOR, "7"),
                Map.of("text", "hi", "times", 3, "big", 4));
        assertEquals(3, text(ints).path("times").intValue());
        assertEquals(4L, ((Map<?, ?>) ints.structuredContent()).get("big"), "a small JSON integer still arrives as a Long");
    }

    @Test
    void theActorComesFromTheContextAndArgumentsCannotOverrideIt() throws Exception {
        for (String forged : List.of("actor", "actorUserId", "actor_user_id", "X-Actor-User-Id")) {
            McpSchema.CallToolResult result = call("echo_sample", Map.of(AgentToolRegistry.CTX_ACTOR, "7"),
                    Map.of("text", "hi", forged, 9));
            assertToolError("BAD_ARG", result);
        }
        assertEquals(0, sample.calls.get(), "a rejected call never reaches the method");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-7", "abc", "7.0", " 7", "07", "7;9", "99999999999999999999"})
    void anUnusableActorHeaderStopsTheCall(String header) throws Exception {
        assertToolError("NO_ACTOR", call("echo_sample", Map.of(AgentToolRegistry.CTX_ACTOR, header), Map.of("text", "hi")));
        assertEquals(0, sample.calls.get());
    }

    @Test
    void aMissingActorHeaderStopsTheCall() throws Exception {
        assertToolError("NO_ACTOR", call("echo_sample", Map.of(), Map.of("text", "hi")));
        assertToolError("NO_ACTOR", call("get_recent_messages", Map.of(AgentToolRegistry.CTX_TRACE_ID, "t-1"), Map.of("chat_id", 5)));
        assertEquals(0, sample.calls.get());
    }

    @Test
    void wrongArgumentsAreToolErrorsInTheSameShape() throws Exception {
        Map<String, Object> actor = Map.of(AgentToolRegistry.CTX_ACTOR, "7");
        assertToolError("BAD_ARG", call("echo_sample", actor, Map.of()));
        assertToolError("BAD_ARG", call("echo_sample", actor, Map.of("text", 5)));
        assertToolError("BAD_ARG", call("echo_sample", actor, Map.of("text", "hi", "times", "3")));
        assertToolError("BAD_ARG", call("list_my_chats", actor, Map.of("type", "channel")));
        assertToolError("BAD_ARG", call("echo_sample", actor, null));
        assertEquals(0, sample.calls.get());
    }

    @Test
    void serviceFailuresMapToCodesAndInternalDetailsStayInside() throws Exception {
        assertToolError("FORBIDDEN", call("get_recent_messages", Map.of(AgentToolRegistry.CTX_ACTOR, "8"), Map.of("chat_id", 5)));
        assertToolError("NOT_FOUND", call("get_user_profile", Map.of(AgentToolRegistry.CTX_ACTOR, "7"), Map.of("user_id", 404)));
        assertToolError("BAD_ARG", call("find_user_by_username", Map.of(AgentToolRegistry.CTX_ACTOR, "7"), Map.of("username", "@")));

        McpSchema.CallToolResult boom = call("boom_sample", Map.of(AgentToolRegistry.CTX_ACTOR, "7"), Map.of());
        assertToolError("INTERNAL", boom);
        assertEquals(Set.of("toolError", "message"), JSON.convertValue(text(boom), Map.class).keySet());
        assertEquals("Tool call failed", text(boom).path("message").asText());
        assertFalse(text(boom).toString().contains("hunter2"));
        assertFalse(text(boom).toString().contains("IllegalStateException"));
    }

    // ---- declarations that must stop the application from starting ----

    public static class Duplicate implements AgentToolProvider {
        @AgentTool(name = "echo_sample", description = "Same name as SampleTools.")
        public Map<String, Object> again(@Actor Long actor) {
            return Map.of();
        }
    }

    public static class UnannotatedParameter implements AgentToolProvider {
        @AgentTool(name = "bad_param", description = "x")
        public Map<String, Object> tool(@Actor Long actor, String chatId) {
            return Map.of();
        }
    }

    public static class NotAMap implements AgentToolProvider {
        @AgentTool(name = "bad_return", description = "x")
        public String tool(@Actor Long actor) {
            return "";
        }
    }

    public static class UnsupportedType implements AgentToolProvider {
        @AgentTool(name = "bad_type", description = "x")
        public Map<String, Object> tool(@ToolParam(name = "flag") Boolean flag) {
            return Map.of();
        }
    }

    public static class OptionalPrimitive implements AgentToolProvider {
        @AgentTool(name = "bad_optional", description = "x")
        public Map<String, Object> tool(@ToolParam(name = "limit", required = false) int limit) {
            return Map.of();
        }
    }

    public static class Hidden implements AgentToolProvider {
        @AgentTool(name = "bad_visibility", description = "x")
        Map<String, Object> tool(@Actor Long actor) {
            return Map.of();
        }
    }

    @Test
    void invalidDeclarationsFailAtStartup() {
        assertTrue(assertThrows(IllegalStateException.class,
                () -> new AgentToolRegistry(List.of(sample, new Duplicate()))).getMessage().contains("echo_sample"));
        for (AgentToolProvider broken : List.of(new UnannotatedParameter(), new NotAMap(), new UnsupportedType(),
                new OptionalPrimitive(), new Hidden())) {
            assertThrows(IllegalStateException.class, () -> new AgentToolRegistry(List.of(broken)),
                    broken.getClass().getSimpleName());
        }
    }
}
