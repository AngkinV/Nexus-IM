package com.nexus.chat.controller.agent;

import com.nexus.chat.security.CurrentUser;
import com.nexus.chat.service.agent.AgentSessionService;
import com.nexus.chat.service.agent.memory.AgentMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/sessions/{sessionId}/memories")
public class AgentSessionMemoryController {
    private final AgentSessionService sessions;
    private final AgentMemoryService memories;
    @GetMapping
    public List<AgentMemoryService.MemoryView> list(@PathVariable String sessionId) {
        Long owner=CurrentUser.require(null);sessions.mustOwn(owner,sessionId);return memories.listSession(owner,sessionId);
    }
    @PostMapping("/{id}/confirm")
    public AgentMemoryService.MemoryView confirm(@PathVariable String sessionId,@PathVariable Long id) {
        Long owner=CurrentUser.require(null);sessions.mustOwn(owner,sessionId);return memories.confirmSessionMemory(owner,sessionId,id);
    }
}
