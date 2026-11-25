package com.nexus.chat.config;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PublicQuotaInterceptorTest {
    static final long MINUTE = 60_000L;
    final AtomicLong clock = new AtomicLong(10_000_000);
    @TempDir Path storage;

    PublicQuotaInterceptor quota(boolean enabled) {
        return new PublicQuotaInterceptor(enabled, 1000, 5000, 3, 2, 400, true, List.of(storage, storage.resolve("missing")), clock::get);
    }

    @AfterEach void signOut() { SecurityContextHolder.clearContext(); }

    void signIn(long userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList()));
    }

    MockHttpServletRequest request(String method, String path) { return new MockHttpServletRequest(method, path); }

    MockHttpServletRequest upload(String path, int bytes) {
        MockHttpServletRequest request = request("POST", path);
        request.setContentType("multipart/form-data; boundary=x");
        if (bytes >= 0) request.setContent(new byte[bytes]);
        return request;
    }

    int status(PublicQuotaInterceptor quota, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean proceed = quota.preHandle(request, response, new Object());
        assertEquals(proceed, response.getStatus() == 200);
        if (!proceed) assertTrue(response.getContentAsString().startsWith("{\"message\":\""));
        return response.getStatus();
    }

    @Test void whenOffNothingIsCountedOrRefused() throws Exception {
        PublicQuotaInterceptor off = quota(false);
        signIn(7);
        Files.write(storage.resolve("full"), new byte[6000]);
        for (int i = 0; i < 20; i++) {
            assertEquals(200, status(off, upload("/api/files/upload", 900)));
            assertEquals(200, status(off, request("POST", "/api/agent/sessions/as_1/chat/stream")));
        }
        assertEquals(200, status(off, upload("/api/companion/assets/model", 10)));
    }

    @Test void anAccountsUploadsAreCappedPerDayAndOtherAccountsAreUnaffected() throws Exception {
        PublicQuotaInterceptor quota = quota(true);
        signIn(7);
        assertEquals(200, status(quota, upload("/api/files/upload", 600)));
        assertEquals(200, status(quota, upload("/api/users/7/avatar", 400)));
        assertEquals(429, status(quota, upload("/api/files/upload/chunk", 1)));
        signIn(8);
        assertEquals(200, status(quota, upload("/api/agent/knowledge/3/documents", 1000)));
        signIn(7);
        clock.addAndGet(24 * 60 * MINUTE - 1);
        assertEquals(429, status(quota, upload("/api/files/upload", 1)));
        clock.addAndGet(1);
        assertEquals(200, status(quota, upload("/api/files/upload", 1000)));
    }

    @Test void anUploadThatHidesItsSizeIsChargedTheLargestAllowed() throws Exception {
        PublicQuotaInterceptor quota = quota(true);
        signIn(7);
        assertEquals(200, status(quota, upload("/api/files/upload", -1)));
        assertEquals(200, status(quota, upload("/api/files/upload", -1)));
        assertEquals(429, status(quota, upload("/api/files/upload", -1)));
    }

    @Test void uploadsStopWhenTheServerStoresTooMuchAndResumeAfterSpaceIsFreed() throws Exception {
        PublicQuotaInterceptor quota = quota(true);
        signIn(7);
        assertEquals(200, status(quota, upload("/api/files/upload", 10)));
        Files.createDirectories(storage.resolve("a/b"));
        Files.write(storage.resolve("a/b/big"), new byte[5000]);
        assertEquals(200, status(quota, upload("/api/files/upload", 10)), "the measurement is cached briefly");
        clock.addAndGet(30_000);
        assertEquals(507, status(quota, upload("/api/files/upload", 10)));
        assertEquals(200, status(quota, request("POST", "/api/messages")));
        Files.delete(storage.resolve("a/b/big"));
        clock.addAndGet(30_000);
        assertEquals(200, status(quota, upload("/api/files/upload", 10)));
    }

    @Test void theSharedCatalogCannotBeChangedButCanBeRead() throws Exception {
        PublicQuotaInterceptor quota = quota(true);
        signIn(7);
        assertEquals(403, status(quota, upload("/api/companion/assets/model", 10)));
        assertEquals(403, status(quota, request("PUT", "/api/companion/assets/model/rename")));
        assertEquals(403, status(quota, request("DELETE", "/api/companion/assets/motion/wave")));
        assertEquals(200, status(quota, request("GET", "/api/companion/assets/model")));
        assertEquals(200, status(quota, upload("/api/companion/user-assets/model", 10)));
        SecurityContextHolder.clearContext();
        assertEquals(403, status(quota, upload("/api/companion/assets/motion", 10)));
    }

    @Test void knowledgeBasesCanBeClosedToWritesWhileStayingReadable() throws Exception {
        PublicQuotaInterceptor closed = new PublicQuotaInterceptor(true, 1000, 5000, 3, 2, 400, false, List.of(storage), clock::get);
        signIn(7);
        assertEquals(403, status(closed, request("POST", "/api/agent/knowledge")));
        assertEquals(403, status(closed, upload("/api/agent/knowledge/3/documents", 10)));
        assertEquals(403, status(closed, request("DELETE", "/api/agent/knowledge/3")));
        assertEquals(200, status(closed, request("GET", "/api/agent/knowledge")));
        assertEquals(200, status(closed, request("POST", "/api/agent/knowledgeable")));
        assertEquals(200, status(quota(true), request("POST", "/api/agent/knowledge")));
        PublicQuotaInterceptor off = new PublicQuotaInterceptor(false, 1000, 5000, 3, 2, 400, false, List.of(storage), clock::get);
        assertEquals(200, status(off, upload("/api/agent/knowledge/3/documents", 10)));
    }

    @Test void modelRequestsAreLimitedToTwoAtATimeAndFinishedOnesFreeTheirSlot() throws Exception {
        PublicQuotaInterceptor quota = new PublicQuotaInterceptor(true, 1000, 5000, 100, 2, 400, true, List.of(storage), clock::get);
        signIn(7);
        MockHttpServletRequest first = request("POST", "/api/agent/sessions/as_1/chat/stream");
        MockHttpServletRequest second = request("POST", "/api/companion/messages/stream");
        assertEquals(200, status(quota, first));
        assertEquals(200, status(quota, second));
        assertEquals(429, status(quota, request("POST", "/api/companion/inner-voice/3")));
        signIn(8);
        assertEquals(200, status(quota, request("POST", "/api/agent/sessions/as_2/chat")));
        signIn(7);

        // The second dispatch of a streamed answer is not a new request, and its completion frees the slot once.
        first.setDispatcherType(DispatcherType.ASYNC);
        assertEquals(200, status(quota, first));
        quota.afterCompletion(first, new MockHttpServletResponse(), new Object(), null);
        quota.afterCompletion(first, new MockHttpServletResponse(), new Object(), null);
        assertEquals(200, status(quota, request("POST", "/api/agent/chats/5/summarize")));
        assertEquals(429, status(quota, request("POST", "/api/agent/providers/9/test")));

        // A request that never reports completion stops holding its slot.
        clock.addAndGet(15 * MINUTE);
        assertEquals(200, status(quota, request("POST", "/api/companion/model-test/3")));
    }

    @Test void modelRequestsAreCappedPerDayAndOrdinaryRequestsAreNotCounted() throws Exception {
        PublicQuotaInterceptor quota = quota(true);
        signIn(7);
        for (int i = 0; i < 50; i++) {
            assertEquals(200, status(quota, request("POST", "/api/messages")));
            assertEquals(200, status(quota, request("GET", "/api/agent/sessions/as_1/chat/stream")));
            assertEquals(200, status(quota, request("POST", "/api/agent/sessions")));
        }
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest call = request("POST", "/api/agent/sessions/as_1/runs/r_1/resume/stream");
            assertEquals(200, status(quota, call));
            quota.afterCompletion(call, new MockHttpServletResponse(), new Object(), null);
        }
        assertEquals(429, status(quota, request("POST", "/api/agent/mcp/servers/4/tools/refresh")));
        clock.addAndGet(24 * 60 * MINUTE);
        assertEquals(200, status(quota, request("POST", "/api/agent/chats/5/todo-extract")));
    }

    @Test void requestsWithoutAnAccountAreLeftForTheHandlerToRefuse() throws Exception {
        PublicQuotaInterceptor quota = quota(true);
        for (int i = 0; i < 10; i++) {
            assertEquals(200, status(quota, upload("/api/files/upload", 900)));
            assertEquals(200, status(quota, request("POST", "/api/companion/messages")));
        }
    }
}
