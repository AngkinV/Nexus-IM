package com.nexus.chat.config;

import com.nexus.chat.service.WebSocketSessionRegistry;
import com.nexus.chat.util.TrustedLogoutNotice;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/** Stops delivery to an already-open revoked connection even if it ignores the logout notification. */
@Component
@RequiredArgsConstructor
public class WebSocketSessionOutboundInterceptor implements ChannelInterceptor {
    private final WebSocketSessionRegistry sessions;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (!sessions.enforced() || TrustedLogoutNotice.isNotice(message)) return message;
        String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        if (!sessions.isSessionAuthorized(sessionId)) {
            sessions.closeSession(sessionId, "session_revoked");
            return null;
        }
        return message;
    }
}
