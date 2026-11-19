package com.nexus.chat.service;

import com.nexus.chat.dto.TavernSessionDTO;
import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import com.nexus.chat.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The tavern's clock: opening an evening, closing it, and the two things that
 * only make sense across evenings — adopting a guest and recalling the last one.
 *
 * The relationship (CompanionConversation) never changes here. Only sessions and
 * the messages filed under them do.
 */
@Slf4j
@Service
public class TavernSessionService {

    /** How many past evenings the archive keeps before the oldest roll off. */
    private static final int ARCHIVE_LIMIT = 30;

    private final TavernSessionRepository sessionRepository;
    private final CompanionRoleRepository roleRepository;
    private final CompanionMessageRepository messageRepository;
    private final CompanionMemoryRepository memoryRepository;
    private final CompanionModelBindingRepository bindingRepository;
    private final ModelCredentialRepository credentialRepository;
    private final CompanionModelService modelService;
    private final CompanionFallbackService fallbackService;
    private final CompanionConversationRepository conversationRepository;

    public TavernSessionService(TavernSessionRepository sessionRepository,
                                CompanionRoleRepository roleRepository,
                                CompanionMessageRepository messageRepository,
                                CompanionMemoryRepository memoryRepository,
                                CompanionModelBindingRepository bindingRepository,
                                ModelCredentialRepository credentialRepository,
                                CompanionModelService modelService,
                                CompanionFallbackService fallbackService,
                                CompanionConversationRepository conversationRepository) {
        this.sessionRepository = sessionRepository;
        this.roleRepository = roleRepository;
        this.messageRepository = messageRepository;
        this.memoryRepository = memoryRepository;
        this.bindingRepository = bindingRepository;
        this.credentialRepository = credentialRepository;
        this.modelService = modelService;
        this.fallbackService = fallbackService;
        this.conversationRepository = conversationRepository;
    }

    /**
     * The evening in progress, opening one if none is. Idempotent on purpose: the
     * client calls this whenever it enters the tavern, and a second call must not
     * start a second evening.
     */
    @Transactional
    public TavernSessionDTO openSession(Long userId, Long roleId, String sceneKey) {
        CompanionRole role = roleRepository.findByIdAndUserId(roleId, userId)
                .orElseThrow(() -> new RuntimeException("role not found"));

        Optional<TavernSession> existing = sessionRepository
                .findFirstByUserIdAndRoleIdAndStatusOrderByStartedAtDesc(userId, roleId, TavernSession.Status.active);

        TavernSession session = existing.orElseGet(() -> {
            TavernSession fresh = new TavernSession();
            fresh.setUserId(userId);
            fresh.setRoleId(roleId);
            fresh.setSceneKey(sceneKey);
            fresh.setStatus(TavernSession.Status.active);
            fresh.setStartedAt(LocalDateTime.now());
            return sessionRepository.save(fresh);
        });

        // Changing the room mid-evening is allowed: it is the same visit, and the
        // prompt reads the scene per message anyway.
        if (sceneKey != null && !sceneKey.equals(session.getSceneKey())) {
            session.setSceneKey(sceneKey);
            sessionRepository.save(session);
        }

        return toDTO(session, role);
    }

    public Optional<TavernSession> findActive(Long userId, Long roleId) {
        return sessionRepository.findFirstByUserIdAndRoleIdAndStatusOrderByStartedAtDesc(
                userId, roleId, TavernSession.Status.active);
    }

    /**
     * Close the evening: ask her for a last line and one sentence worth keeping,
     * file the sentence as an unconfirmed long-term memory, and mark the session
     * closed so the next visit starts clean.
     *
     * Works without a model — the closing line falls back to a plain farewell and
     * nothing is remembered. Closing must never be blocked by a failed request.
     */
    public TavernSessionDTO closeSession(Long userId, Long sessionId) {
        TavernSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new RuntimeException("session not found"));
        if (session.getStatus() == TavernSession.Status.closed) {
            return toDTO(session, null);
        }

        // Read what the closing needs, then let the transaction close before the
        // model call. Holding a DB connection open across a 20-second HTTP request
        // starves the pool and makes every other request wait behind it.
        CompanionRole role = roleRepository.findByIdAndUserId(session.getRoleId(), userId).orElse(null);
        Long conversationId = conversationRepository
                .findByUserIdAndRoleId(userId, session.getRoleId())
                .map(CompanionConversation::getId)
                .orElse(null);
        List<CompanionMessage> history = conversationId == null
                ? List.of()
                : messageRepository.findTop20ByConversationIdOrderByCreatedAtDesc(conversationId);
        java.util.Collections.reverse(history);

        CompanionModelBinding binding = bindingRepository
                .findByUserIdAndRoleId(userId, session.getRoleId()).orElse(null);
        ModelCredential credential = null;
        if (binding != null) {
            credential = credentialRepository
                    .findByUserIdAndProviderAndPurpose(userId, binding.getProvider(), ModelCredential.PURPOSE_CHAT)
                    .or(() -> credentialRepository.findByUserIdAndProvider(userId, binding.getProvider()))
                    .orElse(null);
        }

        CompanionModelService.ClosingReply closing =
                modelService.generateClosing(role, history, binding, credential, session.getSceneKey());

        String closingLine = closing != null ? closing.closingLine() : fallbackService.generateFarewell(role);
        String summary = closing != null ? closing.summary() : null;

        return persistClosing(userId, session, role, closingLine, summary);
    }

    /** The write half of closing, in its own short transaction. */
    @Transactional
    protected TavernSessionDTO persistClosing(Long userId, TavernSession session, CompanionRole role,
                                              String closingLine, String summary) {
        session.setClosingLine(closingLine);
        session.setSummary(summary);
        session.setStatus(TavernSession.Status.closed);
        session.setClosedAt(LocalDateTime.now());
        sessionRepository.save(session);

        // The sentence becomes a memory the user can see and delete — unconfirmed,
        // because they may not want this evening to stick.
        if (summary != null && !summary.isBlank()) {
            CompanionMemory memory = new CompanionMemory();
            memory.setUserId(userId);
            memory.setRoleId(session.getRoleId());
            memory.setMemoryType(CompanionMemory.MemoryType.long_term);
            memory.setContent(summary);
            memory.setConfirmed(false);
            memory.setShared(false);
            memoryRepository.save(memory);
        }

        return toDTO(session, role);
    }

    /** One session by id, for the client to refresh after an action. */
    public TavernSessionDTO describe(Long userId, Long sessionId) {
        TavernSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new RuntimeException("session not found"));
        return toDTO(session, null);
    }

    /** Closed evenings, newest first. */
    public List<TavernSessionDTO> listClosed(Long userId, Long roleId) {
        List<TavernSession> sessions = sessionRepository
                .findByUserIdAndRoleIdAndStatusOrderByStartedAtDesc(userId, roleId, TavernSession.Status.closed);
        List<TavernSessionDTO> out = new ArrayList<>();
        int limit = Math.min(sessions.size(), ARCHIVE_LIMIT);
        for (int i = 0; i < limit; i++) {
            out.add(toDTO(sessions.get(i), null));
        }
        return out;
    }

    /**
     * Bring a guest home: flip them from 过客 to 常驻 so they stay in the seat
     * list instead of the "past encounters" group. The role itself does not
     * change otherwise — same history, same bond.
     */
    @Transactional
    public void adoptRole(Long userId, Long roleId, Long sessionId) {
        CompanionRole role = roleRepository.findByIdAndUserId(roleId, userId)
                .orElseThrow(() -> new RuntimeException("role not found"));
        role.setKind(CompanionRole.Kind.regular);
        if (sessionId != null) role.setOriginSessionId(sessionId);
        roleRepository.save(role);
    }

    /**
     * Invent a guest for an empty seat. Uses the same model channel as the
     * conversation; without one, a neutral preset is used rather than failing —
     * an empty seat should still be fillable offline.
     */
    public CompanionRole inviteRole(Long userId, String sceneKey) {
        // Model call first, outside any transaction — see closeSession.
        CompanionModelBinding binding = bindingRepository
                .findFirstByUserIdAndEnabledTrue(userId).orElse(null);
        ModelCredential credential = null;
        if (binding != null) {
            credential = credentialRepository
                    .findByUserIdAndProviderAndPurpose(userId, binding.getProvider(), ModelCredential.PURPOSE_CHAT)
                    .or(() -> credentialRepository.findByUserIdAndProvider(userId, binding.getProvider()))
                    .orElse(null);
        }

        CompanionModelService.GeneratedRole generated = modelService.generateRole(binding, credential, sceneKey);
        return persistGuest(userId, generated);
    }

    /** The write half of inviting, in its own short transaction. */
    @Transactional
    protected CompanionRole persistGuest(Long userId, CompanionModelService.GeneratedRole generated) {
        CompanionRole role = new CompanionRole();
        role.setUserId(userId);
        role.setKind(CompanionRole.Kind.guest);
        role.setActive(true);
        if (generated != null) {
            role.setName(generated.name());
            role.setTraits(JsonUtil.toJson(splitTraits(generated.traits())));
            role.setTone(generated.tone());
            role.setBaselineMood("平静");
        } else {
            role.setName("无名的旅人");
            role.setTraits(JsonUtil.toJson(List.of("沉默", "谨慎", "不轻易开口")));
            role.setTone("简短、慢、留白多");
            role.setBaselineMood("平静");
        }
        return roleRepository.save(role);
    }

    private List<String> splitTraits(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split("[、,，/\\s]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out.isEmpty() ? List.of("沉默") : out;
    }

    private TavernSessionDTO toDTO(TavernSession session, CompanionRole role) {
        TavernSessionDTO dto = new TavernSessionDTO();
        dto.setId(session.getId());
        dto.setRoleId(session.getRoleId());
        dto.setSceneKey(session.getSceneKey());
        dto.setStatus(session.getStatus().name());
        dto.setStartedAt(session.getStartedAt());
        dto.setClosedAt(session.getClosedAt());
        dto.setClosingLine(session.getClosingLine());
        dto.setSummary(session.getSummary());
        dto.setMessageCount(messageRepository.countBySessionId(session.getId()));
        return dto;
    }
}
