package com.nexus.chat.service.agent.facts;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

/** Canonical versioned opaque token, not a signature or authorization capability. */
public record MessageCursor(long chatId, MessageWindow window, long watermark, LocalDateTime createdAt, long messageId) {
    public MessageCursor {
        if (chatId < 1 || watermark < 1 || messageId < 1 || messageId > watermark || window == null || createdAt == null)
            throw new IllegalArgumentException("invalid cursor position");
        if (window.start() != null && (createdAt.isBefore(window.storedStart()) || !createdAt.isBefore(window.storedEnd())))
            throw new IllegalArgumentException("cursor outside window");
        window.timestamp(createdAt); // reject ambiguous/finer-than-microsecond positions
    }
    public String encode() {
        String s = String.join("|", "1", Long.toString(chatId), window.start() == null ? "" : window.start().toString(),
                window.end() == null ? "" : window.end().toString(), window.timezone().getId(), window.storageTimeZone().getId(),
                Long.toString(watermark), createdAt.toString(), Long.toString(messageId));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
    public static MessageCursor decode(String token, long chatId, MessageWindow window, Long requestedWatermark, long currentHigh) {
        try {
            if (token == null || token.length() > 2048 || !token.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            String[] p = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
            if (p.length != 9 || !p[0].equals("1")) throw new IllegalArgumentException();
            MessageWindow bound = MessageWindow.parse(p[2].isEmpty() ? null : p[2], p[3].isEmpty() ? null : p[3], p[4], MessageWindow.iana(p[5]));
            MessageCursor c = new MessageCursor(Long.parseLong(p[1]), bound, Long.parseLong(p[6]), LocalDateTime.parse(p[7]), Long.parseLong(p[8]));
            if (!c.encode().equals(token) || c.chatId != chatId || !bound.equals(window) || c.watermark > currentHigh
                    || (requestedWatermark != null && requestedWatermark != c.watermark)) throw new IllegalArgumentException();
            return c;
        } catch (RuntimeException e) { throw new IllegalArgumentException("invalid cursor or mismatched chat/window/zone/watermark", e); }
    }
}
