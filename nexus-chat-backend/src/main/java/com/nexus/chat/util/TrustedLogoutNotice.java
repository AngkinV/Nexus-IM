package com.nexus.chat.util;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;

/** A server-only notice may tell a revoked client why it is being disconnected; no business frames bypass auth. */
public final class TrustedLogoutNotice {
    private static final String HEADER = "nexus.internal.logout-notice";
    private static final Object MARKER = new Object();

    private TrustedLogoutNotice() {}

    public static MessageHeaders headers() {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setHeader(HEADER, MARKER);
        headers.setLeaveMutable(true);
        return headers.getMessageHeaders();
    }

    public static boolean isNotice(Message<?> message) {
        // Client native headers and string/boolean copies cannot reproduce this in-process identity.
        return message.getHeaders().get(HEADER) == MARKER;
    }
}
