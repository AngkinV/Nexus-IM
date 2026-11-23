package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic timestamp tests: no sleeps, services or real credentials. T1 runs these. */
class McpInternalRequestVerifierTest {
    private static final String TOKEN = "fixture-only-internal-token";
    private static final String SIGNER = "fixture-only-hmac-material-for-unit-tests";
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    private final McpInternalRequestVerifier verifier = new McpInternalRequestVerifier(json, TOKEN, SIGNER, 1000, clock::get);

    private MockHttpServletRequest request(String body, long timestamp, String nonce) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGNER.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + nonce + "." + digest).getBytes(StandardCharsets.UTF_8)));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/agent/mcp-management/registry");
        request.setContent(bytes);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.addHeader("X-Internal-Service", "nexus-agent-backend");
        request.addHeader("X-Internal-Timestamp", Long.toString(timestamp));
        request.addHeader("X-Internal-Nonce", nonce);
        request.addHeader("X-Internal-Signature", signature);
        request.addHeader("X-Actor-User-Id", "41");
        request.addHeader("X-Trace-Id", "tr_fixture");
        return request;
    }

    private String body() { return "{\"actorUserId\":41,\"traceId\":\"tr_fixture\",\"operation\":\"list\"}"; }

    @Test void exactSignedBodyAndActorAreRequired() throws Exception {
        var verified = verifier.verify(request(body(), clock.get(), "nonce_one"));
        assertEquals(41, verified.actorUserId());
        assertEquals("list", verified.operation());
        String foreign = body().replace("41", "42");
        assertThrows(ResponseStatusException.class, () -> verifier.verify(request(foreign, clock.get(), "nonce_foreign")));
    }

    @Test void missingTokenOrAlteredSignatureIsDeniedWithoutConsumingTheGoodNonce() throws Exception {
        var bad = request(body(), clock.get(), "nonce_valid");
        bad.removeHeader("X-Internal-Signature"); bad.addHeader("X-Internal-Signature", "0".repeat(64));
        assertThrows(ResponseStatusException.class, () -> verifier.verify(bad));
        assertEquals(41, verifier.verify(request(body(), clock.get(), "nonce_valid")).actorUserId());
        var noToken = request(body(), clock.get(), "nonce_no_token"); noToken.removeHeader("Authorization");
        assertThrows(ResponseStatusException.class, () -> verifier.verify(noToken));
    }

    @Test void futureTimestampNonceCoversItsWholeAcceptanceWindowIncludingLastMillisecond() throws Exception {
        long start = clock.get(), timestamp = start + 500;
        verifier.verify(request(body(), timestamp, "nonce_future"));
        clock.set(start + 1001); // First-receipt now+skew has expired; timestamp is still acceptable.
        assertThrows(ResponseStatusException.class, () -> verifier.verify(request(body(), timestamp, "nonce_future")));
        clock.set(timestamp + 1000); // Inclusive timestamp boundary must still reject replay.
        assertThrows(ResponseStatusException.class, () -> verifier.verify(request(body(), timestamp, "nonce_future")));
        clock.incrementAndGet();
        assertThrows(ResponseStatusException.class, () -> verifier.verify(request(body(), timestamp, "nonce_fresh_but_stale_time")));
    }

    @Test void duplicateIdentityFieldsAndOversizedBodiesNeverProduceAnActor() throws Exception {
        String duplicate = "{\"actorUserId\":41,\"actorUserId\":42,\"traceId\":\"tr_fixture\",\"operation\":\"list\"}";
        assertThrows(ResponseStatusException.class, () -> verifier.verify(request(duplicate, clock.get(), "nonce_duplicate")));
        assertThrows(ResponseStatusException.class, () -> verifier.verify(request("x".repeat(65537), clock.get(), "nonce_big")));
    }

    @Test void knownDevelopmentCredentialsDoNotAuthorizeTheNewRegistry() throws Exception {
        var insecure = new McpInternalRequestVerifier(json, "dev-internal-token-change-me", SIGNER, 1000, clock::get);
        assertThrows(ResponseStatusException.class, () -> insecure.verify(request(body(), clock.get(), "nonce_dev")));
    }
}
