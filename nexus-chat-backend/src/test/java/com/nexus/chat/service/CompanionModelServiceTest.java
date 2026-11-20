package com.nexus.chat.service;

import com.nexus.chat.model.CompanionModelBinding;
import com.nexus.chat.model.ModelCredential;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CompanionModelServiceTest {

    private static final String DONE = "data: [DONE]\n\n";
    private final CountDownLatch responseSent = new CountDownLatch(1);
    private final CountDownLatch releaseResponse = new CountDownLatch(1);
    private final ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService clientExecutor = Executors.newSingleThreadExecutor();
    private final CompanionCryptoService crypto = new CompanionCryptoService("local-stream-test");
    private final CompanionModelBinding binding = new CompanionModelBinding();
    private final ModelCredential credential = new ModelCredential();
    private HttpServer server;
    private CompanionModelService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverExecutor);
        server.start();
        binding.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        binding.setModelName("local-test-model");
        credential.setApiKeyEncrypted(crypto.encrypt("local-test-key"));
        service = new CompanionModelService(crypto, new RestTemplateBuilder(), 10, 1200);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // Always unblock the handler, including when a bounded Future.get fails.
        releaseResponse.countDown();
        if (server != null) server.stop(0);
        serverExecutor.shutdownNow();
        clientExecutor.shutdownNow();
        assertTrue(serverExecutor.awaitTermination(3, TimeUnit.SECONDS));
        assertTrue(clientExecutor.awaitTermination(3, TimeUnit.SECONDS));
    }

    @Test
    void streamsContentInOrderAndSkipsNonContentEvents() {
        respond(200, ": heartbeat\n\nevent: message\n\ndata:   \n\n"
                + "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n"
                + "data: not-json\n\n"
                + delta("你好，")
                + "data: {\"choices\":[{\"delta\":{\"content\":null}}]}\n\n"
                + delta("") + delta("请坐。")
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"completion_tokens\":4}}\n\n"
                + DONE);
        List<String> received = new ArrayList<>();

        assertEquals("你好，请坐。", stream(received::add));
        assertEquals(List.of("你好，", "请坐。"), received);
    }

    @Test
    void ignoresContentAfterDone() {
        respond(200, delta("已完成") + "data:[DONE]  \r\n\r\n" + delta("不能追加"));
        List<String> received = new ArrayList<>();

        assertEquals("已完成", stream(received::add));
        assertEquals(List.of("已完成"), received);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "你好"})
    void returnsAtDoneWithoutWaitingForResponseToClose(String content) throws Exception {
        holdOpen((content.isEmpty() ? "" : delta(content)) + DONE);
        List<String> received = new ArrayList<>();
        Future<String> reply = clientExecutor.submit(() -> stream(received::add));

        assertTrue(responseSent.await(3, TimeUnit.SECONDS), "server did not flush DONE");
        // The handler cannot close until tearDown releases it. This deadline is
        // shorter than the service's 10s minimum socket read timeout.
        assertEquals(content.isEmpty() ? null : content, reply.get(3, TimeUnit.SECONDS));
        assertEquals(content.isEmpty() ? List.of() : List.of(content), received);
        assertEquals(1L, releaseResponse.getCount());
    }

    @Test
    void preservesPartialContentWhenEofArrivesWithoutDone() {
        respond(200, delta("半句话") + "data: {\"choices\":[{\"delta\":{\"content\":\"未完成");
        List<String> received = new ArrayList<>();

        assertEquals("半句话", stream(received::add));
        assertEquals(List.of("半句话"), received);
    }

    @Test
    void returnsNullWhenStreamEndsWithoutContent() {
        respond(200, ": heartbeat\n\ndata: invalid-json\n\n" + delta(""));
        List<String> received = new ArrayList<>();

        assertNull(stream(received::add));
        assertTrue(received.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 500})
    void returnsNullOnHttpErrorWithoutDeliveringDeltas(int status) {
        respond(status, "{\"error\":{\"message\":\"local test failure\"}}");
        List<String> received = new ArrayList<>();

        assertNull(stream(received::add));
        assertTrue(received.isEmpty());
    }

    @ParameterizedTest
    @MethodSource("callbackFailures")
    void callbackFailureStopsReadingAndKeepsAlreadyAppendedContent(RuntimeException failure) throws Exception {
        holdOpen(delta("第一段") + delta("第二段") + delta("不能读取"));
        List<String> received = new ArrayList<>();
        Future<String> reply = clientExecutor.submit(() -> stream(piece -> {
            received.add(piece);
            if (received.size() == 2) throw failure;
        }));

        assertTrue(responseSent.await(3, TimeUnit.SECONDS));
        // The delta that triggered the callback failure is already appended.
        assertEquals("第一段第二段", reply.get(3, TimeUnit.SECONDS));
        assertEquals(List.of("第一段", "第二段"), received);
    }

    static Stream<RuntimeException> callbackFailures() {
        return Stream.of(new CompanionModelService.ReaderGone(), new IllegalStateException("callback failed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "保留这段"})
    void ioFailureKeepsPartialContentOrReturnsNullAndClosesBody(String content) {
        // A deterministic transport failure, rather than relying on how a JDK
        // HTTP client interprets a prematurely closed TCP connection.
        InputStream prefix = new ByteArrayInputStream(
                (content.isEmpty() ? "" : delta(content)).getBytes(StandardCharsets.UTF_8));
        CountDownLatch bodyClosed = new CountDownLatch(1);
        InputStream brokenBody = new SequenceInputStream(prefix, new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection interrupted");
            }

            @Override
            public void close() {
                bodyClosed.countDown();
            }
        });
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST,
                URI.create(binding.getEndpoint() + "/chat/completions"));
        request.setResponse(new MockClientHttpResponse(brokenBody, HttpStatus.OK));
        service = new CompanionModelService(crypto,
                new RestTemplateBuilder().requestFactory(settings -> (uri, method) -> request), 10, 1200);
        List<String> received = new ArrayList<>();

        assertEquals(content.isEmpty() ? null : content, stream(received::add));
        assertEquals(content.isEmpty() ? List.of() : List.of(content), received);
        assertEquals(0L, bodyClosed.getCount(), "response body must be closed on failure");
    }

    private String stream(Consumer<String> onDelta) {
        return service.streamReply(CompanionPromptBuilder.Context.bare(null, null), List.of(),
                binding, credential, onDelta);
    }

    private static String delta(String content) {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + content + "\"}}]}\n\n";
    }

    private void handle(HttpHandler handler) {
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                handler.handle(exchange);
            }
        });
    }

    private void respond(int status, String body) {
        handle(exchange -> send(exchange, status, body));
    }

    private void holdOpen(String body) {
        handle(exchange -> {
            send(exchange, 200, body);
            responseSent.countDown();
            try {
                releaseResponse.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        exchange.sendResponseHeaders(status, 0);
        exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }
}
