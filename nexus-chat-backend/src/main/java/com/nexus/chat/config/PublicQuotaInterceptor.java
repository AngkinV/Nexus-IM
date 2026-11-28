package com.nexus.chat.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * Per-account limits for a deployment strangers can reach: how much one account may upload
 * in a day, how much the server stores in total, and how many model requests one account may
 * start. Off unless {@code nexus.quota.enabled} is true (the {@code public} profile sets it).
 * Counters live in memory and start again after a restart; the storage total is read from disk.
 */
@Component
public class PublicQuotaInterceptor implements HandlerInterceptor {
    static final String SLOT = PublicQuotaInterceptor.class.getName() + ".slot";
    private static final long DAY = 24 * 60 * 60_000L;
    /** A model request that never reports completion stops counting as running after this long. */
    private static final long SLOT_EXPIRY = 15 * 60_000L;
    private static final long STORAGE_CACHE = 30_000L;
    /** Every request that makes the server call a model on the account's behalf. */
    private static final List<String> MODEL_CALLS = List.of(
            "/api/agent/sessions/*/chat", "/api/agent/sessions/*/chat/stream", "/api/agent/sessions/*/runs/*/resume/stream",
            "/api/agent/chats/*/summarize", "/api/agent/chats/*/todo-extract", "/api/agent/chats/*/reply-suggest",
            "/api/agent/providers/*/test", "/api/agent/mcp/servers/*/test", "/api/agent/mcp/servers/*/tools/refresh",
            "/api/companion/messages", "/api/companion/messages/stream", "/api/companion/inner-voice/*",
            "/api/companion/model-test/*", "/api/companion/skills/*/versions/*/trial");
    private static final AntPathMatcher PATHS = new AntPathMatcher();

    private static final class Usage {
        long windowStart;

        Usage(long firstUse) {
            windowStart = firstUse;
        }

        long uploadedBytes;
        int modelCalls;
        final ArrayDeque<Long> running = new ArrayDeque<>();
    }

    private final Map<Long, Usage> usage = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final long uploadBytesPerDay;
    private final long storageBytes;
    private final int modelCallsPerDay;
    private final int concurrentModelCalls;
    private final long assumedUploadBytes;
    private final boolean knowledgeBaseWritable;
    private final List<Path> storageRoots;
    private final LongSupplier clock;
    private long storageMeasuredAt = Long.MIN_VALUE;
    private long storageUsed;

    @Autowired
    public PublicQuotaInterceptor(@Value("${nexus.quota.enabled:false}") boolean enabled,
                                  @Value("${nexus.quota.upload-bytes-per-user-per-day:524288000}") long uploadBytesPerDay,
                                  @Value("${nexus.quota.storage-bytes:5368709120}") long storageBytes,
                                  @Value("${nexus.quota.model-calls-per-user-per-day:200}") int modelCallsPerDay,
                                  @Value("${nexus.quota.concurrent-model-calls-per-user:2}") int concurrentModelCalls,
                                  @Value("${nexus.quota.assumed-upload-bytes:68157440}") long assumedUploadBytes,
                                  @Value("${nexus.quota.knowledge-base-writable:true}") boolean knowledgeBaseWritable,
                                  @Value("${nexus.quota.storage-roots:uploads,storage}") List<String> storageRoots) {
        this(enabled, uploadBytesPerDay, storageBytes, modelCallsPerDay, concurrentModelCalls, assumedUploadBytes,
                knowledgeBaseWritable, storageRoots.stream().map(Path::of).toList(), System::currentTimeMillis);
    }

    PublicQuotaInterceptor(boolean enabled, long uploadBytesPerDay, long storageBytes, int modelCallsPerDay,
                           int concurrentModelCalls, long assumedUploadBytes, boolean knowledgeBaseWritable,
                           List<Path> storageRoots, LongSupplier clock) {
        this.knowledgeBaseWritable = knowledgeBaseWritable;
        this.enabled = enabled;
        this.uploadBytesPerDay = uploadBytesPerDay;
        this.storageBytes = storageBytes;
        this.modelCallsPerDay = modelCallsPerDay;
        this.concurrentModelCalls = concurrentModelCalls;
        this.assumedUploadBytes = assumedUploadBytes;
        this.storageRoots = storageRoots;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        // A streamed answer is dispatched a second time when it completes; it was counted the first time.
        if (!enabled || request.getDispatcherType() != DispatcherType.REQUEST) return true;
        String method = request.getMethod();
        if (method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());

        // The shared model and motion catalog is served to every account, so no account may change it.
        if (path.startsWith("/api/companion/assets/")) {
            return refuse(response, HttpStatus.FORBIDDEN, "公共资源目录为只读");
        }
        // Ingesting a document runs third-party parsers and a vector store over a stranger's file.
        if (!knowledgeBaseWritable && (path.equals("/api/agent/knowledge") || path.startsWith("/api/agent/knowledge/"))) {
            return refuse(response, HttpStatus.FORBIDDEN, "此环境未开放知识库");
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long userId)) return true; // the handler answers 401

        long now = clock.getAsLong();
        Usage mine = usage.computeIfAbsent(userId, id -> new Usage(now));
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/")) {
            if (storageUsed(now) >= storageBytes) {
                return refuse(response, HttpStatus.INSUFFICIENT_STORAGE, "服务器存储空间已满");
            }
            // An upload that does not declare its size is charged the largest one a request may carry.
            long size = request.getContentLengthLong() >= 0 ? request.getContentLengthLong() : assumedUploadBytes;
            synchronized (mine) {
                roll(mine, now);
                if (mine.uploadedBytes + size > uploadBytesPerDay) {
                    return refuse(response, HttpStatus.TOO_MANY_REQUESTS, "今日上传量已达上限");
                }
                mine.uploadedBytes += size;
            }
        }
        if (method.equals("POST") && MODEL_CALLS.stream().anyMatch(pattern -> PATHS.match(pattern, path))) {
            synchronized (mine) {
                roll(mine, now);
                mine.running.removeIf(started -> now - started >= SLOT_EXPIRY);
                if (mine.modelCalls >= modelCallsPerDay) {
                    return refuse(response, HttpStatus.TOO_MANY_REQUESTS, "今日 AI 调用次数已达上限");
                }
                if (mine.running.size() >= concurrentModelCalls) {
                    return refuse(response, HttpStatus.TOO_MANY_REQUESTS, "同时进行的 AI 请求过多，请等上一个完成");
                }
                mine.modelCalls++;
                mine.running.add(now);
                request.setAttribute(SLOT, new long[]{userId, now});
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
        if (!(request.getAttribute(SLOT) instanceof long[] slot)) return;
        request.removeAttribute(SLOT);
        Usage mine = usage.get(slot[0]);
        if (mine == null) return;
        synchronized (mine) {
            mine.running.removeFirstOccurrence(slot[1]);
        }
    }

    private void roll(Usage mine, long now) {
        if (now - mine.windowStart >= DAY) {
            mine.windowStart = now;
            mine.uploadedBytes = 0;
            mine.modelCalls = 0;
        }
    }

    private synchronized long storageUsed(long now) {
        if (storageMeasuredAt != Long.MIN_VALUE && now - storageMeasuredAt < STORAGE_CACHE) return storageUsed;
        long total = 0;
        for (Path root : storageRoots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                total += files.filter(Files::isRegularFile).mapToLong(file -> {
                    try {
                        return Files.size(file);
                    } catch (IOException gone) {
                        return 0;
                    }
                }).sum();
            } catch (IOException | java.io.UncheckedIOException unreadable) {
                // Count what could be read; a file deleted mid-walk is not a reason to refuse uploads.
            }
        }
        storageMeasuredAt = now;
        storageUsed = total;
        return total;
    }

    private static boolean refuse(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"" + message + "\"}");
        return false;
    }
}
