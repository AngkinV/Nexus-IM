package com.nexus.chat.service.agent.facts;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.chat.model.Message;
import com.nexus.chat.repository.agent.AgentMessageQueryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Bounded data reader, NOT a tool provider or authorization entry point.
 * T2 must authorize/intersect the effective chat/window before EVERY invocation.
 * T1/T2 integrate the existing MCP tool via the separate small patch.
 */
@Service
public class AgentMessageReader {
    public static final int DEFAULT_LIMIT = 80;
    public static final int MAX_LIMIT = 200;
    public static final int DEFAULT_RESULT_BYTES = 65_536;

    private final AgentMessageQueryRepository repository;
    private final ObjectMapper compactMapper;
    private final ZoneId storageZone;
    private final int serverResultBytes;

    public AgentMessageReader(AgentMessageQueryRepository repository, ObjectMapper mapper,
            @Value("${agent.message-query.storage-zone:}") String configuredStorageZone,
            @Value("${agent.message-query.max-result-bytes:65536}") int serverResultBytes) {
        this.repository = repository;
        // Entity LocalDateTime is a projection of legacy TIMESTAMP values;
        // JDBC/session conversion can differ from the JVM zone. T1 must verify
        // and configure that projection convention. Bean creation remains
        // compatible, but new window reads fail closed while it is unknown.
        this.storageZone = configuredStorageZone == null || configuredStorageZone.isBlank()
                ? null : MessageWindow.iana(configuredStorageZone);
        if (this.storageZone != null && !ZoneId.getAvailableZoneIds().contains(this.storageZone.getId())) {
            throw new IllegalArgumentException("configure an explicit IANA message storage zone");
        }
        if (serverResultBytes < 1 || serverResultBytes > DEFAULT_RESULT_BYTES) {
            throw new IllegalArgumentException("message result policy must be within 1..65536 bytes");
        }
        this.serverResultBytes = serverResultBytes;
        this.compactMapper = mapper.copy()
                .disable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.compactMapper.getFactory().configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, false);
    }

    public Map<String, Object> read(long chatId, Integer requestedLimit,
            String startAt, String endAt, String timezone, String cursorToken,
            Long requestedWatermark, Integer requestedResultBytes,
            Function<Set<Long>, Map<Long, String>> senderNames) {
        if (storageZone == null) {
            throw new IllegalStateException("verify JDBC/session timestamp projection and configure agent.message-query.storage-zone before window reads");
        }
        if (chatId < 1 || senderNames == null) {
            throw new IllegalArgumentException("chat and real sender lookup are required");
        }
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be within 1..200");
        }
        int resultCap = requestedResultBytes == null ? serverResultBytes : requestedResultBytes;
        if (resultCap < 1 || resultCap > serverResultBytes) {
            throw new IllegalArgumentException("max_result_bytes can only tighten the server policy");
        }
        if (requestedWatermark != null && requestedWatermark < 0) {
            throw new IllegalArgumentException("watermark must be nonnegative");
        }
        MessageWindow window = MessageWindow.parse(startAt, endAt, timezone, storageZone);
        long currentHigh = repository.highWatermark(chatId);
        if (requestedWatermark != null && requestedWatermark > currentHigh) {
            throw new IllegalArgumentException("watermark exceeds current message high watermark");
        }
        MessageCursor cursor = cursorToken == null ? null
                : MessageCursor.decode(cursorToken, chatId, window, requestedWatermark, currentHigh);
        long watermark = cursor != null ? cursor.watermark()
                : requestedWatermark == null ? currentHigh : requestedWatermark;
        List<Message> rows = repository.read(chatId, window, watermark, cursor, limit + 1);
        verifyRows(rows, chatId, window, watermark, cursor, limit + 1);
        Set<Long> senderIds = new LinkedHashSet<>();
        for (Message message : rows.subList(0, Math.min(limit, rows.size()))) {
            senderIds.add(message.getSenderId());
        }
        Map<Long, String> names = senderIds.isEmpty() ? Map.of() : senderNames.apply(Set.copyOf(senderIds));
        if (names == null) {
            throw new IllegalStateException("real sender lookup returned no mapping");
        }
        List<Map<String, Object>> emitted = new ArrayList<>();
        for (Message message : rows.subList(0, Math.min(limit, rows.size()))) {
            Map<String, Object> full = evidence(message, names, window);
            List<Map<String, Object>> candidate = append(emitted, full);
            if (encodedSize(page(chatId, rows.size(), limit, candidate, window, watermark)) <= resultCap) {
                emitted.add(full);
                continue;
            }
            String body = message.getContent();
            if (body == null || body.isEmpty()) {
                break; // No body can be shortened without changing evidence.
            }
            Map<String, Object> clipped = new LinkedHashMap<>(full);
            clipped.put("content", "");
            clipped.put("contentTruncated", true);
            if (encodedSize(page(chatId, rows.size(), limit, append(emitted, clipped), window, watermark)) > resultCap) {
                break;
            }
            // Never split a UTF-16 surrogate pair; always actually shorten.
            int lo = 0, hi = body.codePointCount(0, body.length()) - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                clipped.put("content", body.substring(0, body.offsetByCodePoints(0, mid)));
                if (encodedSize(page(chatId, rows.size(), limit, append(emitted, clipped), window, watermark)) <= resultCap) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            clipped.put("content", body.substring(0, body.offsetByCodePoints(0, lo)));
            emitted.add(clipped);
            break;
        }
        if (!rows.isEmpty() && emitted.isEmpty()) {
            throw new IllegalArgumentException("RESULT_LIMIT: minimum message evidence does not fit");
        }
        Map<String, Object> result = page(chatId, rows.size(), limit, emitted, window, watermark);
        if (encodedSize(result) > resultCap) {
            throw new IllegalArgumentException("RESULT_LIMIT: minimum page metadata does not fit");
        }
        return result;
    }

    private static List<Map<String, Object>> append(List<Map<String, Object>> prefix, Map<String, Object> item) {
        List<Map<String, Object>> result = new ArrayList<>(prefix);
        result.add(item);
        return result;
    }

    private static Map<String, Object> evidence(Message message, Map<Long, String> names, MessageWindow window) {
        Map<String, Object> value = new LinkedHashMap<>();
        String timestamp = window.timestamp(message.getCreatedAt());
        value.put("messageId", message.getId());
        value.put("chatId", message.getChatId());
        value.put("timestamp", timestamp);
        value.put("createdAt", timestamp); // compatible key, now unambiguous offset
        value.put("senderId", message.getSenderId());
        value.put("senderName", names.getOrDefault(message.getSenderId(), "unknown"));
        value.put("content", message.getContent());
        value.put("contentTruncated", false);
        value.put("originalContentBytes", message.getContent() == null ? 0
                : message.getContent().getBytes(StandardCharsets.UTF_8).length);
        return value;
    }

    private Map<String, Object> page(long chatId, int readCount, int limit,
            List<Map<String, Object>> emitted, MessageWindow window, long watermark) {
        boolean more = readCount > emitted.size();
        String next = null;
        if (more && !emitted.isEmpty()) {
            Map<String, Object> last = emitted.get(emitted.size() - 1);
            // createdAt is stored separately from emitted offset text in the
            // original row; map the exact instant back into the storage zone.
            LocalDateTime position = java.time.OffsetDateTime.parse((String) last.get("timestamp"))
                    .atZoneSameInstant(window.storageTimeZone()).toLocalDateTime();
            next = new MessageCursor(chatId, window, watermark, position, (Long) last.get("messageId")).encode();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("chatId", chatId);
        value.put("messages", List.copyOf(emitted));
        value.put("window", window.metadata());
        value.put("timezone", window.timezone().getId());
        value.put("storageTimeZone", window.storageTimeZone().getId());
        value.put("watermark", watermark);
        value.put("nextCursor", next);
        value.put("readCount", readCount);
        value.put("returnedCount", emitted.size());
        value.put("truncatedCount", readCount - emitted.size());
        value.put("probeCount", readCount == limit + 1 ? 1 : 0);
        value.put("contentTruncatedCount", emitted.stream().filter(m -> Boolean.TRUE.equals(m.get("contentTruncated"))).count());
        value.put("hasMore", more);
        value.put("resultBytes", 0);
        for (int i = 0; i < 8; i++) {
            int size = encodedSize(value);
            if (((Integer) value.get("resultBytes")) == size) {
                return value;
            }
            value.put("resultBytes", size);
        }
        throw new IllegalStateException("page byte count did not converge");
    }

    private int encodedSize(Map<String, Object> value) {
        try {
            return compactMapper.writeValueAsBytes(value).length;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("message page cannot be encoded", e);
        }
    }

    private static void verifyRows(List<Message> rows, long chatId, MessageWindow window,
            long watermark, MessageCursor cursor, int maxRows) {
        if (rows == null || rows.size() > maxRows) {
            throw new IllegalStateException("message query exceeded its bound");
        }
        LocalDateTime previousTime = cursor == null ? null : cursor.createdAt();
        long previousId = cursor == null ? Long.MAX_VALUE : cursor.messageId();
        for (Message message : rows) {
            LocalDateTime created = message.getCreatedAt();
            if (message.getId() == null || message.getId() < 1 || message.getId() > watermark
                    || message.getChatId() == null || message.getChatId() != chatId
                    || message.getSenderId() == null || message.getSenderId() < 1
                    || created == null || Boolean.TRUE.equals(message.getIsRecalled())
                    || (window.start() != null && (created.isBefore(window.storedStart()) || !created.isBefore(window.storedEnd())))
                    || (previousTime != null && (created.isAfter(previousTime)
                        || (created.equals(previousTime) && message.getId() >= previousId)))) {
                throw new IllegalStateException("message query returned inconsistent scope/window/cursor evidence");
            }
            // Reject ambiguous legacy timestamps rather than invent an offset.
            window.timestamp(created);
            previousTime = created;
            previousId = message.getId();
        }
    }
}
