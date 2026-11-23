package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.chat.security.AgentInternalTokenFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Dedicated Python→Java data-channel validator; no change to the global signing protocol. */
@Service
public class McpInternalRequestVerifier {
    private final String internalToken;
    private final String signingSecret;
    private final long skewMs;
    private final ObjectMapper json;
    private final java.util.function.LongSupplier clock;
    private final ConcurrentHashMap<String, Long> nonces = new ConcurrentHashMap<>();

    @Autowired
    public McpInternalRequestVerifier(ObjectMapper json,
            @Value("${agent.internal.token:}") String internalToken,
            @Value("${agent.internal.signing-secret:}") String signingSecret,
            @Value("${agent.internal.nonce-skew-ms:300000}") long skewMs) {
        this(json, internalToken, signingSecret, skewMs, System::currentTimeMillis);
    }

    McpInternalRequestVerifier(ObjectMapper json, String internalToken, String signingSecret, long skewMs,
                               java.util.function.LongSupplier clock) {
        this.json = json;
        this.internalToken = internalToken;
        this.signingSecret = signingSecret;
        this.skewMs = Math.max(1000, Math.min(300000, skewMs));
        this.clock = clock;
    }

    private ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "MCP_INTERNAL_AUTH_REQUIRED");
    }

    public Verified verify(HttpServletRequest request) {
        if (!AgentInternalTokenFilter.bearerMatches(internalToken, request.getHeader("Authorization"))
                || signingSecret == null || signingSecret.isBlank() || "dev-signing-secret-change-me".equals(signingSecret)
                || "dev-internal-token-change-me".equals(internalToken)
                || !"nexus-agent-backend".equals(request.getHeader("X-Internal-Service"))) throw denied();
        try {
            String timestamp = request.getHeader("X-Internal-Timestamp");
            String nonce = request.getHeader("X-Internal-Nonce");
            String signature = request.getHeader("X-Internal-Signature");
            String actor = request.getHeader("X-Actor-User-Id");
            String trace = request.getHeader("X-Trace-Id");
            if (timestamp == null || timestamp.length() > 19 || nonce == null
                    || !nonce.matches("[A-Za-z0-9_-]{1,128}") || signature == null
                    || !signature.matches("[a-f0-9]{64}") || actor == null || actor.length() > 19
                    || trace == null || !trace.matches("[A-Za-z0-9_-]{1,128}")) throw denied();
            long at = Long.parseLong(timestamp);
            long userId = Long.parseLong(actor);
            long now = clock.getAsLong();
            if (userId <= 0 || at < now - skewMs || at > now + skewMs) throw denied();
            // Bound even chunked bodies before parsing or hashing. No raw body logging.
            byte[] body = request.getInputStream().readNBytes(65537);
            if (body.length > 65536) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "MCP_SIZE_LIMIT");
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + nonce + "." + digest).getBytes(StandardCharsets.UTF_8)));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII))) throw denied();
            nonces.entrySet().removeIf(entry -> entry.getValue() <= now);
            if (nonces.size() >= 10000 || nonces.putIfAbsent(nonce, at + skewMs + 1) != null) throw denied();
            JsonNode parsed = json.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            if (!(parsed instanceof ObjectNode payload)) throw denied();
            payload.fieldNames().forEachRemaining(key -> {
                if (!Set.of("actorUserId", "traceId", "operation", "serverId").contains(key)) throw denied();
            });
            if (!payload.path("actorUserId").isIntegralNumber() || !payload.path("actorUserId").canConvertToLong()
                    || payload.path("actorUserId").asLong() != userId || !trace.equals(payload.path("traceId").asText())
                    || !Set.of("list", "get").contains(payload.path("operation").asText())) throw denied();
            String operation = payload.path("operation").asText();
            if (operation.equals("get") && (!payload.path("serverId").isTextual()
                    || !payload.path("serverId").asText().matches("[A-Za-z0-9_-]{1,96}"))) throw denied();
            if (operation.equals("list") && payload.has("serverId")) throw denied();
            return new Verified(userId, trace, operation, payload.path("serverId").asText(null));
        } catch (ResponseStatusException controlled) {
            throw controlled;
        } catch (Exception ignored) {
            // Raised without a cause: headers/invalid JSON/credentials never cross the boundary.
            throw denied();
        }
    }

    public record Verified(long actorUserId, String traceId, String operation, String serverId) { }
    @Override public String toString() { return "McpInternalRequestVerifier[redacted]"; }
}
