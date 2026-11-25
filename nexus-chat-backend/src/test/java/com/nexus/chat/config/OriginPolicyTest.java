package com.nexus.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.controller.FunnelReadinessController;
import com.nexus.chat.repository.UserSessionRepository;
import com.nexus.chat.security.JwtAuthenticationFilter;
import com.nexus.chat.security.JwtTokenProvider;
import com.nexus.chat.service.PresenceService;
import com.nexus.chat.service.WebSocketSessionRegistry;
import org.junit.jupiter.api.Nested;
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
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;

/**
 * Origins against a real Tomcat with the application's own {@link SecurityConfig},
 * {@link WebSocketConfig} and property files: HTTP CORS, the SockJS endpoint /ws
 * and the native endpoint /ws-native must agree on who is let in.
 *
 * The CORS filter sits in front of the WebSocket endpoints and would hide a
 * WebSocket list that is wider than the HTTP one, so each mode is also run
 * without the security chain, where only the endpoints' own check can refuse.
 */
class OriginPolicyTest {
    private static final String QODER = "https://nexus-chat-2026-fwohlcnoevd.qoder.zone";
    private static final List<String> LOCAL_CLIENTS = List.of("http://localhost:5173", "http://localhost:3000", "http://localhost:5174",
            "http://127.0.0.1:5173", "app://nexus-chat");
    private static final List<String> BUILT_IN = List.of("http://localhost:5173", "http://localhost:3000", "http://localhost:5174",
            "http://127.0.0.1:5173", "https://chat.angkin.cn", "https://www.angkin.cn",
            "https://nexus-chat-fwohlcnoevd.qoder.zone", "app://nexus-chat");
    private static final List<String> UNKNOWN = List.of("https://evil.example", QODER + ".evil.example",
            "http://nexus-chat-2026-fwohlcnoevd.qoder.zone", "https://other-site.qoder.zone",
            "https://angkin.cn.evil.example", "http://localhost.evil.example:5173");
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Configuration
    @Profile("origin-policy-fixture")
    @ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, ServletWebServerFactoryAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class, ErrorMvcAutoConfiguration.class, WebSocketServletAutoConfiguration.class})
    @Import({AllowedOrigins.class, WebSocketConfig.class})
    static class WebSocketOnly {
        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return mock(JwtTokenProvider.class);
        }

        @Bean
        UserSessionRepository userSessionRepository() {
            return mock(UserSessionRepository.class);
        }

        @Bean
        WebSocketAuthChannelInterceptor webSocketAuthChannelInterceptor(JwtTokenProvider tokens, UserSessionRepository sessions) {
            return new WebSocketAuthChannelInterceptor(tokens, mock(WebSocketSessionRegistry.class), mock(PresenceService.class), sessions);
        }

        @Bean
        MessageValidationInterceptor messageValidationInterceptor() {
            return new MessageValidationInterceptor(mock(WebSocketRateLimiter.class));
        }
    }

    @Configuration
    @Profile("origin-policy-fixture")
    @ImportAutoConfiguration({SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
    @Import({WebSocketOnly.class, SecurityConfig.class, JwtAuthenticationFilter.class, FunnelReadinessController.class, FunnelSecretGuard.class})
    static class FullChain {
    }

    private static HttpResponse<String> send(int port, String method, String path, String origin, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin);
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int handshake(int port, String path, String origin) {
        try {
            HTTP.newWebSocketBuilder().header("Origin", origin)
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + path), new WebSocket.Listener() {
                    }).join().abort();
            return 101;
        } catch (CompletionException refused) {
            return ((WebSocketHandshakeException) refused.getCause()).getResponse().statusCode();
        }
    }

    private static void assertHttp(boolean accepted, int port, String origin) throws Exception {
        HttpResponse<String> preflight = send(port, "OPTIONS", "/api/chats", origin,
                "Access-Control-Request-Method", "GET", "Access-Control-Request-Headers", "authorization");
        // No controller is mapped here, so an accepted request ends as 404 and a refused one never gets that far.
        HttpResponse<String> actual = send(port, "GET", "/api/auth/origin-probe", origin);
        if (accepted) {
            assertEquals(200, preflight.statusCode(), origin);
            assertEquals(origin, preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null), origin);
            assertNotEquals(403, actual.statusCode(), origin);
            assertEquals(origin, actual.headers().firstValue("Access-Control-Allow-Origin").orElse(null), origin);
            return;
        }
        for (HttpResponse<String> response : List.of(preflight, actual)) {
            assertEquals(403, response.statusCode(), origin);
            assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent(), origin);
        }
    }

    private static void assertWebSocket(boolean accepted, int port, String origin) throws Exception {
        assertEquals(accepted ? 200 : 403, send(port, "GET", "/ws/info", origin).statusCode(), "/ws/info " + origin);
        assertEquals(accepted ? 101 : 403, handshake(port, "/ws/000/" + UUID.randomUUID() + "/websocket", origin), "/ws websocket " + origin);
        assertEquals(accepted ? 101 : 403, handshake(port, "/ws-native", origin), "/ws-native " + origin);
    }

    @Nested
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @ActiveProfiles("origin-policy-fixture")
    @SpringBootTest(classes = FullChain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class DefaultMode {
        @LocalServerPort
        int port;

        @Test
        void theLocalSafetyProbeIsNotInstalledOutsideTheFunnelProfile() throws Exception {
            assertEquals(404, send(port, "GET", "/internal/funnel/readiness", QODER).statusCode());
        }

        @Test
        void earlierOriginsAndTheCurrentQoderSiteReachHttpAndBothWebSocketEndpoints() throws Exception {
            for (String origin : BUILT_IN) {
                assertHttp(true, port, origin);
                assertWebSocket(true, port, origin);
            }
            assertHttp(true, port, QODER);
            assertWebSocket(true, port, QODER);
        }

        @Test
        void unknownOriginsAreRefusedOnHttpAndBothWebSocketEndpoints() throws Exception {
            for (String origin : UNKNOWN) {
                assertHttp(false, port, origin);
                assertWebSocket(false, port, origin);
            }
        }
    }

    @Nested
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @ActiveProfiles("origin-policy-fixture")
    @SpringBootTest(classes = WebSocketOnly.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class DefaultModeWithoutTheCorsFilter {
        @LocalServerPort
        int port;

        @Test
        void theWebSocketEndpointsRefuseUnknownOriginsOnTheirOwn() throws Exception {
            for (String origin : UNKNOWN) assertWebSocket(false, port, origin);
            for (String origin : BUILT_IN) assertWebSocket(true, port, origin);
            assertWebSocket(true, port, QODER);
        }
    }

    @Nested
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @ActiveProfiles({"funnel", "origin-policy-fixture"})
    @SpringBootTest(classes = FullChain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"jwt.secret=fixture-only-not-for-use-outside-tests-jwt",
                    "agent.internal.token=fixture-only-not-for-use-outside-tests-token",
                    "agent.internal.signing-secret=fixture-only-not-for-use-outside-tests-signing",
                    "companion.api-key.secret=fixture-only-not-for-use-outside-tests-encryption"})
    class FunnelProfile {
        @LocalServerPort
        int port;
        @Autowired
        AllowedOrigins origins;

        @Test
        void theRealSecurityChainAndFunnelProfileProvideTheLoopbackReadinessContract() throws Exception {
            HttpResponse<String> response = send(port, "GET", "/internal/funnel/readiness", QODER);
            assertEquals(200, response.statusCode());
            var value = new ObjectMapper().readTree(response.body());
            assertEquals(true, value.path("ready").asBoolean());
            assertEquals(FunnelReadinessController.POLICY_VERSION, value.path("policyVersion").asText());
            assertEquals(QODER, value.path("origins").get(0).asText());
            assertEquals(QODER, value.path("publicOrigin").asText());
            assertEquals(4, value.path("origins").size());
        }

        @Test
        void theProfileKeepsOnePublicOriginAlongsideLoopbackAndElectronClients() {
            assertEquals(List.of(QODER, "http://localhost:*", "http://127.0.0.1:*", "app://*"), origins.patterns());
        }

        @Test
        void publishedQoderAndLocalClientsReachHttpAndBothWebSocketEndpoints() throws Exception {
            assertHttp(true, port, QODER);
            assertWebSocket(true, port, QODER);
            for (String origin : LOCAL_CLIENTS) {
                assertHttp(true, port, origin);
                assertWebSocket(true, port, origin);
            }
        }

        @Test
        void otherPublicOriginsAndUnknownOnesAreRefusedEverywhere() throws Exception {
            for (List<String> group : List.of(BUILT_IN, UNKNOWN)) {
                for (String origin : group) {
                    if (LOCAL_CLIENTS.contains(origin)) continue;
                    assertHttp(false, port, origin);
                    assertWebSocket(false, port, origin);
                }
            }
        }
    }

    @Nested
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @ActiveProfiles({"funnel", "origin-policy-fixture"})
    @SpringBootTest(classes = WebSocketOnly.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class FunnelProfileWithoutTheCorsFilter {
        @LocalServerPort
        int port;

        @Test
        void webSocketEndpointsAllowLocalClientsButRejectOtherPublicOriginsOnTheirOwn() throws Exception {
            assertWebSocket(true, port, QODER);
            for (String origin : LOCAL_CLIENTS) assertWebSocket(true, port, origin);
            for (List<String> group : List.of(BUILT_IN, UNKNOWN)) {
                for (String origin : group) {
                    if (!LOCAL_CLIENTS.contains(origin)) assertWebSocket(false, port, origin);
                }
            }
        }
    }
}
