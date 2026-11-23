package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/** Encoding regressions only; no transport/probe validation claim. */
class McpCatalogDigestTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void emptyCatalogHasExactVersionedEncoding() throws Exception {
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("mcp-catalog-v1:A0[]".getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, McpCatalogDigest.version(json.createArrayNode()));
    }

    @Test void whitespaceAndRuntimeLimitsDoNotChangeCatalogVersion() throws Exception {
        ArrayNode first = (ArrayNode) json.readTree("[{\"toolId\":\"s:t\",\"inputSchema\":{\"type\":\"object\",\"default\":1.0},\"allowed\":false,\"timeoutSec\":10}]");
        ArrayNode second = (ArrayNode) json.readTree("[ { \"timeoutSec\":20, \"allowed\":true, \"inputSchema\": {\"default\":1.0, \"type\":\"object\"}, \"toolId\":\"s:t\" } ]");
        assertEquals(McpCatalogDigest.version(first), McpCatalogDigest.version(second));
    }

    @Test void integerAndFloatingLiteralKindsAreNotConfused() throws Exception {
        ArrayNode integer = (ArrayNode) json.readTree("[{\"toolId\":\"s:t\",\"inputSchema\":{\"default\":1}}]");
        ArrayNode floating = (ArrayNode) json.readTree("[{\"toolId\":\"s:t\",\"inputSchema\":{\"default\":1.0}}]");
        assertNotEquals(McpCatalogDigest.version(integer), McpCatalogDigest.version(floating));
    }

    @Test void equivalentExponentSpellingsHaveSameBinary64Encoding() throws Exception {
        ArrayNode first = (ArrayNode) json.readTree("[{\"toolId\":\"s:t\",\"inputSchema\":{\"maximum\":1e20}}]");
        ArrayNode second = (ArrayNode) json.readTree("[{\"toolId\":\"s:t\",\"inputSchema\":{\"maximum\":1.0E20}}]");
        assertEquals(McpCatalogDigest.version(first), McpCatalogDigest.version(second));
    }
}
