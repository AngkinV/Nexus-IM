package com.nexus.chat.service.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Passes a server-sent event stream through unchanged while reporting each complete
 * public frame it sees.
 *
 * The client must receive exactly the bytes the agent service produced, so nothing
 * here rewrites, delays or buffers them: every write goes straight to the wrapped
 * stream. Frame parsing is a side effect, and a listener that fails is dropped
 * rather than allowed to break the stream.
 *
 * Frames are split on bytes and decoded only once complete, so a multi-byte
 * character split across two writes still decodes correctly. An end of input is
 * not a frame delimiter: a half-written frame at EOF is never reported.
 */
public class SseRecordingOutputStream extends OutputStream {

    private final OutputStream delegate;
    private final BiConsumer<String, Object> listener;
    private final AgentRunService runService;
    private final Long userId;
    private final String sessionId;
    private final String runId;
    private final String generation;
    private final boolean leased;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private boolean listenerFailed;

    public SseRecordingOutputStream(OutputStream delegate, BiConsumer<String, Object> listener) {
        this(delegate, listener, null, null, null, null);
    }

    public SseRecordingOutputStream(OutputStream delegate, BiConsumer<String, Object> listener,
                                    AgentRunService runService, Long userId, String sessionId, String runId) {
        this(delegate, listener, runService, userId, sessionId, runId, null, false);
    }

    public SseRecordingOutputStream(OutputStream delegate, BiConsumer<String, Object> listener,
            AgentRunService runService, Long userId, String sessionId, String runId, String generation) {
        this(delegate, listener, runService, userId, sessionId, runId, generation, true);
    }

    private SseRecordingOutputStream(OutputStream delegate, BiConsumer<String, Object> listener,
            AgentRunService runService, Long userId, String sessionId, String runId, String generation, boolean leased) {
        this.generation = generation;
        this.leased = leased;
        this.delegate = delegate;
        this.listener = listener;
        this.runService = runService;
        this.userId = userId;
        this.sessionId = sessionId;
        this.runId = runId;
    }

    @Override
    public void write(int b) throws IOException {
        delegate.write(b);
        pending.write(b);
        drain();
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        delegate.write(bytes, offset, length);
        pending.write(bytes, offset, length);
        drain();
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    private void drain() {
        byte[] buffered = pending.toByteArray();
        int consumed = 0;
        int boundary;
        while ((boundary = endOfFrame(buffered, consumed)) >= 0) {
            String frame = new String(buffered, consumed, boundary - consumed, StandardCharsets.UTF_8);
            consumed = boundary;
            report(frame);
        }
        if (consumed > 0) {
            pending.reset();
            pending.write(buffered, consumed, buffered.length - consumed);
        }
    }

    /** The index just past the blank line that ends a frame, or -1 when none is complete yet. */
    private static int endOfFrame(byte[] buffer, int from) {
        int crlf = -1;
        int lf = -1;
        for (int index = from; index + 1 < buffer.length; index++) {
            if (lf < 0 && buffer[index] == '\n' && buffer[index + 1] == '\n') {
                lf = index + 2;
            }
            if (crlf < 0 && index + 3 < buffer.length && buffer[index] == '\r' && buffer[index + 1] == '\n'
                    && buffer[index + 2] == '\r' && buffer[index + 3] == '\n') {
                crlf = index + 4;
            }
            if (lf >= 0 || crlf >= 0) break;
        }
        if (crlf < 0 && lf < 0) return -1;
        if (crlf < 0) return lf;
        if (lf < 0) return crlf;
        return Math.min(crlf, lf);
    }

    private void report(String frame) {
        if (listenerFailed) return;
        String name = null;
        List<String> data = new ArrayList<>();
        for (String line : frame.split("\r?\n")) {
            if (line.startsWith("event:")) {
                name = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                data.add(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        if (name == null || !AgentRunService.PUBLIC_EVENTS.contains(name)) return;
        String body = String.join("\n", data);
        Object parsed;
        try {
            parsed = body.isEmpty() ? null : mapper.readValue(body, Object.class);
        } catch (Exception ex) {
            return;  // a frame we cannot read is not a frame the client can use either
        }
        if (!(parsed instanceof Map<?, ?>)) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) parsed;
        try {
            listener.accept(name, payload);
            if (runService != null) {
                if (leased) runService.record(userId, sessionId, runId, generation, name, payload);
                else runService.record(userId, sessionId, runId, name, payload);
            }
        } catch (RuntimeException ex) {
            // Storing the trace is best effort: a failure here must not disturb the stream.
            listenerFailed = true;
        }
    }
}
