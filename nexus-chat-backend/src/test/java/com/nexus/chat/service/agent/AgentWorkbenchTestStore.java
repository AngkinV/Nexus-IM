package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.model.agent.AgentRunRecord;
import com.nexus.chat.model.agent.AgentSession;
import com.nexus.chat.model.agent.AgentWorkspace;
import com.nexus.chat.repository.agent.AgentRunRecordRepository;
import com.nexus.chat.repository.agent.AgentSessionRepository;
import com.nexus.chat.repository.agent.AgentWorkspaceRepository;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Snapshotting repository doubles: no database, Redis, application context or user data. */
public final class AgentWorkbenchTestStore {
    public final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    public final AgentSessionRepository sessions = mock(AgentSessionRepository.class);
    public final AgentRunRecordRepository runs = mock(AgentRunRecordRepository.class);
    public final AgentWorkspaceRepository workspaces = mock(AgentWorkspaceRepository.class);
    public final Map<String, AgentRunRecord> savedRuns = new LinkedHashMap<>();
    public final Map<String, AgentWorkspace> savedWorkspaces = new LinkedHashMap<>();
    public final AtomicInteger runSaves = new AtomicInteger();
    private final Map<String, AgentSession> ownedSessions = new LinkedHashMap<>();
    private long nextId = 1;

    public AgentWorkbenchTestStore() {
        addSession(7L, "as_test");
        addSession(7L, "as_other");
        addSession(8L, "as_foreign");
        when(sessions.findBySessionId(anyString())).thenAnswer(inv -> Optional.ofNullable(ownedSessions.get(inv.getArgument(0))));
        when(sessions.findBySessionIdAndUserId(anyString(), anyLong())).thenAnswer(inv ->
                Optional.ofNullable(ownedSessions.get(inv.getArgument(0)))
                        .filter(s -> s.getUserId().equals(inv.getArgument(1))));
        when(runs.existsByRunId(anyString())).thenAnswer(inv -> savedRuns.containsKey(inv.getArgument(0)));
        when(runs.findByUserIdAndSessionIdAndRunId(anyLong(), anyString(), anyString())).thenAnswer(inv -> {
            AgentRunRecord record = savedRuns.get(inv.getArgument(2));
            return record != null && record.getUserId().equals(inv.getArgument(0))
                    && record.getSessionId().equals(inv.getArgument(1)) ? Optional.of(copy(record)) : Optional.empty();
        });
        when(runs.findByUserIdAndSessionIdOrderByStartedAtAscIdAsc(anyLong(), anyString())).thenAnswer(inv -> savedRuns.values().stream()
                .filter(r -> r.getUserId().equals(inv.getArgument(0)) && r.getSessionId().equals(inv.getArgument(1)))
                .sorted(Comparator.comparing(AgentRunRecord::getStartedAt).thenComparing(AgentRunRecord::getId))
                .map(this::copy).toList());
        when(runs.saveAndFlush(any(AgentRunRecord.class))).thenAnswer(inv -> {
            AgentRunRecord record = inv.getArgument(0);
            if (record.getId() == null) record.setId(nextId++);
            savedRuns.put(record.getRunId(), copy(record));
            runSaves.incrementAndGet();
            return record;
        });
        doAnswer(inv -> {
            savedRuns.values().removeIf(r -> r.getUserId().equals(inv.getArgument(0)) && r.getSessionId().equals(inv.getArgument(1)));
            return null;
        }).when(runs).deleteByUserIdAndSessionId(anyLong(), anyString());
        when(workspaces.findByUserIdAndSessionId(anyLong(), anyString())).thenAnswer(inv -> {
            AgentWorkspace workspace = savedWorkspaces.get(inv.getArgument(1));
            return workspace != null && workspace.getUserId().equals(inv.getArgument(0))
                    ? Optional.of(copy(workspace)) : Optional.empty();
        });
        when(workspaces.saveAndFlush(any(AgentWorkspace.class))).thenAnswer(inv -> {
            AgentWorkspace workspace = inv.getArgument(0);
            if (workspace.getId() == null) workspace.setId(nextId++);
            savedWorkspaces.put(workspace.getSessionId(), copy(workspace));
            return workspace;
        });
        doAnswer(inv -> {
            AgentWorkspace workspace = savedWorkspaces.get(inv.getArgument(1));
            if (workspace != null && workspace.getUserId().equals(inv.getArgument(0))) savedWorkspaces.remove(inv.getArgument(1));
            return null;
        }).when(workspaces).deleteByUserIdAndSessionId(anyLong(), anyString());
    }

    /** One shared authority, so every runService() sees the same grants. */
    public final com.nexus.chat.agent.AgentScopeTestDouble authority =
            new com.nexus.chat.agent.AgentScopeTestDouble();

    public AgentRunService runService() {
        AgentRunService service = new AgentRunService(mapper, runs, sessions);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "scopes", authority.service());
        return service;
    }

    /** Record the run's own scope so its output stays readable to its owner. */
    public void grant(AgentRunService service, String runId) {
        service.replaceScope(7L, "as_test", runId, authority.grantFor(7L, "as_test", runId, 9L));
    }

    private void addSession(Long userId, String sessionId) {
        AgentSession session = new AgentSession();
        session.setId(nextId++);
        session.setUserId(userId);
        session.setSessionId(sessionId);
        ownedSessions.put(sessionId, session);
    }

    private AgentRunRecord copy(AgentRunRecord record) {
        return mapper.convertValue(record, AgentRunRecord.class);
    }

    private AgentWorkspace copy(AgentWorkspace workspace) {
        return mapper.convertValue(workspace, AgentWorkspace.class);
    }
}
