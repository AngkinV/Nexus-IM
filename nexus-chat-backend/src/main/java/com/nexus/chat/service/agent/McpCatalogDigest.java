package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Same typed UTF-8 / IEEE-754 encoding as Python app.mcp_catalog (v1). */
public final class McpCatalogDigest {
    private static final List<String> FIELDS = List.of("toolId", "serverId", "name", "remoteName",
            "description", "inputSchema", "readOnlyHint", "risk");
    private McpCatalogDigest() { }

    private static int utf8Order(String left, String right) {
        return java.util.Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    public static String version(ArrayNode tools) {
        if (tools.size() > 128) throw new IllegalArgumentException("MCP catalog size");
        List<JsonNode> ordered = new ArrayList<>();
        tools.forEach(ordered::add);
        ordered.sort((left, right) -> utf8Order(left.path("toolId").asText(), right.path("toolId").asText()));
        ArrayNode observed = JsonNodeFactory.instance.arrayNode();
        for (JsonNode tool : ordered) {
            ObjectNode item = JsonNodeFactory.instance.objectNode();
            for (String field : FIELDS) item.set(field, tool.has(field) ? tool.get(field) : NullNode.instance);
            observed.add(item);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes("mcp-catalog-v1:".getBytes(StandardCharsets.US_ASCII));
        encode(observed, bytes, 0);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (Exception ignored) { throw new IllegalStateException("MCP catalog digest unavailable"); }
    }

    private static void text(ByteArrayOutputStream bytes, String value) {
        bytes.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void encode(JsonNode value, ByteArrayOutputStream bytes, int depth) {
        if (depth > 32 || bytes.size() > 2097152) throw new IllegalArgumentException("MCP catalog size");
        if (value.isNull()) text(bytes, "N;");
        else if (value.isBoolean()) text(bytes, value.asBoolean() ? "T;" : "F;");
        else if (value.isIntegralNumber()) text(bytes, "I" + value.bigIntegerValue().toString() + ";");
        else if (value.isFloatingPointNumber()) {
            if (!Double.isFinite(value.doubleValue())) throw new IllegalArgumentException("MCP catalog number");
            text(bytes, "R" + String.format(java.util.Locale.ROOT, "%016x", Double.doubleToRawLongBits(value.doubleValue())) + ";");
        } else if (value.isTextual()) {
            String string = value.asText();
            // No unpaired surrogate aliasing between JVM and Python UTF-8 encoders.
            for (int i = 0; i < string.length(); i++) {
                char character = string.charAt(i);
                if (Character.isHighSurrogate(character)) {
                    if (++i >= string.length() || !Character.isLowSurrogate(string.charAt(i))) throw new IllegalArgumentException("MCP catalog string");
                } else if (Character.isLowSurrogate(character)) throw new IllegalArgumentException("MCP catalog string");
            }
            byte[] raw = string.getBytes(StandardCharsets.UTF_8);
            text(bytes, "S" + raw.length + ":");
            bytes.writeBytes(raw);
        } else if (value.isArray()) {
            text(bytes, "A" + value.size() + "[");
            for (JsonNode item : value) encode(item, bytes, depth + 1);
            text(bytes, "]");
        } else if (value.isObject()) {
            List<String> keys = new ArrayList<>();
            value.fieldNames().forEachRemaining(keys::add);
            keys.sort(McpCatalogDigest::utf8Order);
            text(bytes, "O" + keys.size() + "{");
            for (String key : keys) {
                encode(JsonNodeFactory.instance.textNode(key), bytes, depth + 1);
                encode(value.get(key), bytes, depth + 1);
            }
            text(bytes, "}");
        } else throw new IllegalArgumentException("MCP catalog JSON");
        if (bytes.size() > 2097152) throw new IllegalArgumentException("MCP catalog size");
    }
}
