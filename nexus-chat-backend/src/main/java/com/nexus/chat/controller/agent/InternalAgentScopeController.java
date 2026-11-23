package com.nexus.chat.controller.agent;

import com.nexus.chat.service.agent.scope.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

/**
 * Internal scope authority for the Python agent.
 *
 * <p>Success stays a bare {@code {valid:true,scope}} body so the agent's
 * strict parser is unchanged. A {@code tool} block only re-checks resource
 * arguments of an already-committed call; the response still carries no
 * message bodies or projection data.
 */
@RestController
@RequiredArgsConstructor
public class InternalAgentScopeController {
    private final AgentScopeService scopes;
    private final AgentScopeToolGuard toolGuard;

    @PostMapping("/internal/agent/scope/validate")
    public Map<String,Object> validate(@RequestHeader("X-Actor-User-Id") String actor, @RequestBody ScopeDtos.Validation body) {
        if(body.actorUserId()==null||!actor.equals(body.actorUserId().toString())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var current = body.tool() == null
                ? scopes.validate(body.actorUserId(), body.sessionId(), body.runId(), body.scope())
                : toolGuard.authorize(body.actorUserId(), body.sessionId(), body.runId(), body.scope(),
                        body.tool().name(), body.tool().arguments());
        return Map.of("valid", true, "scope", current);
    }
}
