package com.nexus.chat.service.agent.facts;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneOffsetTransition;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit instant window over legacy, zone-less timestamps. Not authorization. */
public record MessageWindow(Instant start, Instant end, ZoneId timezone, ZoneId storageTimeZone) {
    public MessageWindow {
        if ((start == null) != (end == null) || (start != null && !start.isBefore(end))) {
            throw new IllegalArgumentException("start_at/end_at must form a nonempty [start,end) pair");
        }
        if (timezone == null || storageTimeZone == null) {
            throw new IllegalArgumentException("zones required");
        }
        if (start != null) {
            // A DATETIME/LocalDateTime cannot disambiguate a repeated DST hour.
            // Reject windows touching a storage-zone offset change instead of
            // reading too much/little or inventing an instant for legacy rows.
            ZoneOffsetTransition transition = storageTimeZone.getRules().nextTransition(start.minusNanos(1));
            if (transition != null && !transition.getInstant().isAfter(end)) {
                throw new IllegalArgumentException("legacy storage window touches an offset transition");
            }
            LocalDateTime from = LocalDateTime.ofInstant(start, storageTimeZone);
            LocalDateTime to = LocalDateTime.ofInstant(end, storageTimeZone);
            if (!from.isBefore(to) || storageTimeZone.getRules().getValidOffsets(from).size() != 1
                    || storageTimeZone.getRules().getValidOffsets(to).size() != 1) {
                throw new IllegalArgumentException("legacy storage window is ambiguous");
            }
        }
    }

    public static MessageWindow parse(String start, String end, String zone, ZoneId storage) {
        try {
            ZoneId output = zone == null ? storage : iana(zone);
            return new MessageWindow(parseBound(start), parseBound(end), output, storage);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("offset RFC3339 bounds and IANA timezone required", e);
        }
    }

    private static Instant parseBound(String value) {
        if (value == null) {
            return null;
        }
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?(?:Z|[+-]\\d{2}:\\d{2})")) {
            throw new IllegalArgumentException("RFC3339 seconds/offset and at most microsecond precision required");
        }
        Instant instant = OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        int year = instant.atOffset(ZoneOffset.UTC).getYear();
        if (year < 1 || year > 9999) {
            throw new IllegalArgumentException("UTC bounds must be within years 1..9999");
        }
        return instant;
    }

    public static ZoneId iana(String zone) {
        if (!ZoneId.getAvailableZoneIds().contains(zone)) {
            throw new IllegalArgumentException("IANA timezone required");
        }
        return ZoneId.of(zone);
    }

    public LocalDateTime storedStart() {
        return start == null ? null : LocalDateTime.ofInstant(start, storageTimeZone);
    }

    public LocalDateTime storedEnd() {
        return end == null ? null : LocalDateTime.ofInstant(end, storageTimeZone);
    }

    public String timestamp(LocalDateTime value) {
        if (value == null || value.getNano() % 1000 != 0
                || storageTimeZone.getRules().getValidOffsets(value).size() != 1) {
            throw new IllegalArgumentException("legacy message timestamp is ambiguous or finer than microseconds");
        }
        return value.atZone(storageTimeZone).withZoneSameInstant(timezone)
                .toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public Map<String, Object> metadata() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("startAt", start == null ? null : start.toString());
        value.put("endAt", end == null ? null : end.toString());
        value.put("semantics", "[start,end)");
        return value;
    }
}
