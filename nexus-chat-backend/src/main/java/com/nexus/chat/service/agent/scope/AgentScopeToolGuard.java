package com.nexus.chat.service.agent.scope;

import com.nexus.chat.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

/**
 * Authorizes one already-committed tool call before its cached result is reused.
 *
 * <p>The scope itself is validated by {@link AgentScopeService}; this class only
 * decides whether that scope covers the specific call's resource arguments. It
 * never reads message bodies: a {@code message_id} is resolved through the
 * non-body chatId/timestamp projection first, exactly like a live tool call.
 */
@Service
@RequiredArgsConstructor
public class AgentScopeToolGuard {

    private static final Set<String> FORBIDDEN = Set.of(
            "scope", "scopeKey", "actorUserId", "actor_user_id", "grantId",
            "authorizationVersion", "sessionGeneration", "sessionId", "runId");

    private static final Set<String> CHAT_KEYS = Set.of("chat_id", "chatId");
    private static final Set<String> USER_KEYS = Set.of("user_id", "userId", "contact_user_id");
    private static final Set<String> MESSAGE_KEYS = Set.of("message_id", "messageId");

    private final AgentScopeService scopes;
    private final MessageRepository messages;

    @Value("${agent.data-time-zone:Asia/Shanghai}")
    private String dataTimeZone = "Asia/Shanghai";

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Scope denied");
    }

    private ZoneId storageZone() {
        return ZoneId.of(dataTimeZone);
    }

    /**
     * @throws ResponseStatusException 403 when the current grant or the call's
     *         resource arguments are outside the authorized scope.
     */
    public ScopeDtos.AuthorizedScope authorize(Long actor, String session, String run,
                                               ScopeDtos.AuthorizedScope scope,
                                               String toolName, Map<String, Object> arguments) {
        var current = scopes.validate(actor, session, run, scope);
        if (toolName == null || toolName.isBlank() || arguments == null) throw denied();
        for (String key : arguments.keySet()) {
            if (FORBIDDEN.contains(key)) throw denied();
        }
        if (current.chatIds().isEmpty() && current.contactUserIds().isEmpty()) throw denied();

        for (String key : CHAT_KEYS) {
            if (arguments.containsKey(key)) {
                long chatId = positive(arguments.get(key));
                if (!current.chatIds().contains(chatId)) throw denied();
            }
        }
        for (String key : USER_KEYS) {
            if (arguments.containsKey(key)) {
                long userId = positive(arguments.get(key));
                if (!current.contactUserIds().contains(userId)) throw denied();
            }
        }
        for (String key : MESSAGE_KEYS) {
            if (arguments.containsKey(key)) {
                long messageId = positive(arguments.get(key));
                var attribution = messages.findScopeAttribution(messageId).orElseThrow(AgentScopeToolGuard::denied);
                if (!current.chatIds().contains(attribution.getChatId())) throw denied();
                LocalDateTime at = attribution.getCreatedAt();
                if (at == null) throw denied();
                var from = current.timeRange().fromLocal(storageZone());
                var to = current.timeRange().toLocal(storageZone());
                if (from != null && at.isBefore(from)) throw denied();
                if (to != null && !at.isBefore(to)) throw denied();
            }
        }
        return current;
    }

    private static long positive(Object value) {
        if (value instanceof Number n) {
            long id = n.longValue();
            if (id > 0 && (n instanceof Integer || n instanceof Long || n instanceof Short)) return id;
        }
        if (value instanceof String s && s.matches("[1-9]\\d{0,17}")) return Long.parseLong(s);
        throw denied();
    }
}
