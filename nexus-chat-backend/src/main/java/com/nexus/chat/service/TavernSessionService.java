package com.nexus.chat.service;

import com.nexus.chat.dto.TavernSessionDTO;
import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import com.nexus.chat.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;
import java.util.Map;

/**
 * The tavern's clock: opening an evening, closing it, and the two things that
 * only make sense across evenings — adopting a guest and recalling the last one.
 *
 * The relationship (CompanionConversation) never changes here. Only sessions and
 * the messages filed under them do.
 *
 * Model calls here always run outside a transaction, and the writes that follow
 * go through {@link TransactionTemplate}. An annotation on a method of this class
 * called from this class would not open a transaction at all (the call never
 * passes through the proxy), which is how the two "short transactions" this
 * service used to declare were silently not transactions.
 */
@Slf4j
@Service
public class TavernSessionService {

    /** How many past evenings the archive keeps before the oldest roll off. */
    private static final int ARCHIVE_LIMIT = 30;
    /** How much of the evening the closing prompt reads. */
    private static final int CLOSING_HISTORY = 30;
    /** 409 reason while this account's previous invite has not finished. */
    public static final String INVITE_IN_PROGRESS = "invite_in_progress";

    private final TavernSessionRepository sessionRepository;
    private final CompanionRoleRepository roleRepository;
    private final CompanionMessageRepository messageRepository;
    private final CompanionMemoryRepository memoryRepository;
    private final CompanionModelResolver modelResolver;
    private final CompanionModelService modelService;
    private final CompanionFallbackService fallbackService;
    private final CompanionConversationRepository conversationRepository;
    private final CompanionStatusRepository statusRepository;
    private final TransactionTemplate tx;
    private final CompanionConversationGuard conversationGuard;
    private final CompanionModelAssetCatalog modelAssetCatalog;
    private final UserAssetService userAssetService;
    /** Accounts with an invite in flight. */
    private final Set<Long> inviting = ConcurrentHashMap.newKeySet();

    public TavernSessionService(TavernSessionRepository sessionRepository,
                                CompanionRoleRepository roleRepository,
                                CompanionMessageRepository messageRepository,
                                CompanionMemoryRepository memoryRepository,
                                CompanionModelResolver modelResolver,
                                CompanionModelService modelService,
                                CompanionFallbackService fallbackService,
                                CompanionConversationRepository conversationRepository,
                                CompanionStatusRepository statusRepository,
                                PlatformTransactionManager transactionManager,
                                CompanionConversationGuard conversationGuard,
                                CompanionModelAssetCatalog modelAssetCatalog,
                                UserAssetService userAssetService) {
        this.sessionRepository = sessionRepository;
        this.roleRepository = roleRepository;
        this.messageRepository = messageRepository;
        this.memoryRepository = memoryRepository;
        this.modelResolver = modelResolver;
        this.modelService = modelService;
        this.fallbackService = fallbackService;
        this.conversationRepository = conversationRepository;
        this.statusRepository = statusRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.conversationGuard = conversationGuard;
        this.modelAssetCatalog = modelAssetCatalog;
        this.userAssetService = userAssetService;
    }

    /**
     * The evening in progress, opening one if none is. Idempotent.
     *
     * The conversation itself opens an evening when the first line is said (see
     * CompanionService#exchange), so the client only calls this to move an
     * evening that is already running into another room.
     */
    public TavernSessionDTO openSession(Long userId, Long roleId, String sceneKey) {
        try (var ignored = conversationGuard.acquire(userId, roleId)) {
            return openSessionLocked(userId, roleId, sceneKey);
        }
    }

    private TavernSessionDTO openSessionLocked(Long userId, Long roleId, String sceneKey) {
        roleRepository.findByIdAndUserId(roleId, userId)
                .orElseThrow(() -> new RuntimeException("role not found"));

        return tx.execute(status -> {
            TavernSession session = sessionRepository
                    .findFirstByUserIdAndRoleIdAndStatusOrderByStartedAtDesc(userId, roleId, TavernSession.Status.active)
                    .orElseGet(() -> {
                        TavernSession fresh = new TavernSession();
                        fresh.setUserId(userId);
                        fresh.setRoleId(roleId);
                        fresh.setSceneKey(sceneKey);
                        fresh.setStatus(TavernSession.Status.active);
                        fresh.setStartedAt(LocalDateTime.now());
                        return sessionRepository.save(fresh);
                    });

            // Changing the room mid-evening is allowed: it is the same visit.
            if (sceneKey != null && !sceneKey.equals(session.getSceneKey())) {
                session.setSceneKey(sceneKey);
                sessionRepository.save(session);
            }
            return toDTO(session);
        });
    }

    public Optional<TavernSession> findActive(Long userId, Long roleId) {
        return sessionRepository.findFirstByUserIdAndRoleIdAndStatusOrderByStartedAtDesc(
                userId, roleId, TavernSession.Status.active);
    }

    /**
     * Close the evening: ask her for a last line and one sentence worth keeping,
     * file the line as the evening's final message and the sentence as an
     * unconfirmed long-term memory, and mark the session closed so the next
     * visit starts clean.
     *
     * Works without a model — the closing line falls back to a plain farewell and
     * nothing is remembered. Closing must never be blocked by a failed request.
     */
    public TavernSessionDTO closeSession(Long userId, Long sessionId) {
        TavernSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new RuntimeException("session not found"));
        try (var ignored = conversationGuard.acquire(userId, session.getRoleId())) {
            return closeSessionLocked(userId, sessionId);
        }
    }

    private TavernSessionDTO closeSessionLocked(Long userId, Long sessionId) {
        TavernSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new RuntimeException("session not found"));
        if (session.getStatus() == TavernSession.Status.closed) {
            return toDTO(session);
        }

        CompanionRole role = roleRepository.findByIdAndUserId(session.getRoleId(), userId).orElse(null);

        // This evening only — not the tail of the whole relationship.
        List<CompanionMessage> history = new ArrayList<>(messageRepository
                .findBySessionIdOrderByIdDesc(sessionId, PageRequest.of(0, CLOSING_HISTORY)));
        Collections.reverse(history);

        // An evening in which nothing was said has nothing to close over: no
        // model call, no farewell to an empty room.
        if (history.isEmpty()) {
            return tx.execute(status -> {
                session.setStatus(TavernSession.Status.closed);
                session.setClosedAt(LocalDateTime.now());
                sessionRepository.save(session);
                return toDTO(session);
            });
        }

        CompanionModelResolver.Resolved resolved = modelResolver.resolve(userId, session.getRoleId());
        CompanionModelService.ClosingReply closing = resolved.usable()
                ? modelService.generateClosing(role, history, resolved.binding(), resolved.credential(), session.getSceneKey())
                : null;

        boolean written = closing != null;
        String closingLine = written ? closing.closingLine() : fallbackService.generateFarewell(role);
        String summary = written ? closing.summary() : null;
        Long conversationId = history.get(0).getConversationId();

        return tx.execute(status -> {
            // Re-read inside the transaction: a second close that raced this one
            // must not file a second farewell.
            TavernSession current = sessionRepository.findByIdAndUserId(sessionId, userId)
                    .orElseThrow(() -> new RuntimeException("session not found"));
            if (current.getStatus() == TavernSession.Status.closed) return toDTO(current);

            current.setClosingLine(closingLine);
            current.setSummary(summary);
            current.setStatus(TavernSession.Status.closed);
            current.setClosedAt(LocalDateTime.now());
            sessionRepository.save(current);

            // Her last line is part of the conversation, so it is stored as one —
            // it used to exist only in the browser and vanish on reload.
            CompanionMessage last = new CompanionMessage();
            last.setConversationId(conversationId);
            last.setUserId(userId);
            last.setRoleId(current.getRoleId());
            last.setSenderType(CompanionMessage.SenderType.role);
            last.setContent(closingLine);
            last.setFallback(!written);
            last.setSessionId(current.getId());
            messageRepository.save(last);

            // The sentence becomes a memory the user can see and delete —
            // unconfirmed, because they may not want this evening to stick.
            if (summary != null && !summary.isBlank()) {
                CompanionMemory memory = new CompanionMemory();
                memory.setUserId(userId);
                memory.setRoleId(current.getRoleId());
                memory.setMemoryType(CompanionMemory.MemoryType.long_term);
                memory.setContent(summary);
                memory.setConfirmed(false);
                memory.setShared(false);
                memoryRepository.save(memory);
            }
            return toDTO(current);
        });
    }

    /** One session by id, for the client to refresh after an action. */
    public TavernSessionDTO describe(Long userId, Long sessionId) {
        TavernSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new RuntimeException("session not found"));
        return toDTO(session);
    }

    /** Closed evenings in which something was said, newest first. */
    public List<TavernSessionDTO> listClosed(Long userId, Long roleId) {
        List<TavernSession> sessions = sessionRepository
                .findByUserIdAndRoleIdAndStatusOrderByStartedAtDesc(userId, roleId, TavernSession.Status.closed);
        List<TavernSessionDTO> out = new ArrayList<>();
        for (TavernSession session : sessions) {
            if (out.size() >= ARCHIVE_LIMIT) break;
            TavernSessionDTO dto = toDTO(session);
            // Evenings opened by merely looking at a character (before sessions
            // waited for the first line) hold nothing worth listing.
            if (dto.getMessageCount() != null && dto.getMessageCount() > 0) out.add(dto);
        }
        return out;
    }

    /**
     * Bring a guest home: flip them from 过客 to 常驻 so they stay in the seat
     * list instead of the "past encounters" group. The role itself does not
     * change otherwise — same history, same bond.
     */
    public void adoptRole(Long userId, Long roleId, Long sessionId) {
        CompanionRole role = roleRepository.findByIdAndUserId(roleId, userId)
                .orElseThrow(() -> new RuntimeException("role not found"));
        if (sessionId != null) {
            TavernSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found"));
            if (!roleId.equals(session.getRoleId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "session does not belong to role");
            }
        }
        role.setKind(CompanionRole.Kind.regular);
        if (sessionId != null) role.setOriginSessionId(sessionId);
        roleRepository.save(role);
    }

    /**
     * Invent a guest for an empty seat. Uses the account's model channel; without
     * one a neutral preset is used rather than failing — an empty seat should
     * still be fillable offline.
     *
     * The 3D model comes from the same catalogue the settings panel lists. A
     * guest used to be saved with no model at all, so an invited character stood
     * on an empty stage with no explanation.
     */
    public CompanionRole inviteRole(Long userId, String sceneKey) {
        return inviteRole(userId, sceneKey, null);
    }

    /**
     * @param requestedModelUrl a model chosen by the user: a shared one
     *                          ("/models/x.vrm") or one from the account's own
     *                          library; null/blank to let the tavern pick one
     * @throws ResponseStatusException 409 {@link #INVITE_IN_PROGRESS} while this
     *         account's previous invite is still running; 400 with one of the
     *         catalogue's codes when the chosen model cannot be worn; 500 when
     *         the models directory cannot be read
     */
    public CompanionRole inviteRole(Long userId, String sceneKey, String requestedModelUrl) {
        // One invite per account at a time: two tabs inviting together would read
        // the same "in use" set and seat the same face twice. In-process only,
        // like the conversation guard — it does not span several backend nodes.
        if (!inviting.add(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, INVITE_IN_PROGRESS);
        }
        try {
            return inviteRoleLocked(userId, sceneKey, requestedModelUrl);
        } finally {
            inviting.remove(userId);
        }
    }

    private CompanionRole inviteRoleLocked(Long userId, String sceneKey, String requestedModelUrl) {
        // The disk first: a model that cannot be worn must fail the request
        // before a model call is paid for, not after.
        CompanionModelAssetCatalog.ModelAsset asset = resolveGuestModel(userId, requestedModelUrl);

        // Model call next, outside any transaction — see the class comment.
        CompanionModelResolver.Resolved resolved = modelResolver.resolveForUser(userId);
        CompanionModelService.GeneratedRole generated = resolved.usable()
                ? modelService.generateRole(resolved.binding(), resolved.credential(), sceneKey)
                : null;

        return tx.execute(status -> {
            CompanionRole role = new CompanionRole();
            role.setUserId(userId);
            role.setKind(CompanionRole.Kind.guest);
            role.setActive(true);
            role.setBaselineMood("平静");
            if (asset != null) {
                role.setModelUrl(asset.fileUrl());
                role.setModelType(asset.type());
            }
            if (generated != null) {
                role.setName(generated.name());
                role.setTraits(JsonUtil.toJson(splitTraits(generated.traits())));
                role.setTone(generated.tone());
                role.setBackground(generated.background());
                role.setGreeting(generated.greeting());
            } else {
                role.setName("无名的旅人");
                role.setTraits(JsonUtil.toJson(List.of("沉默", "谨慎", "不轻易开口")));
                role.setTone("简短、慢、留白多");
            }
            CompanionRole saved = roleRepository.save(role);

            // What the model said she is doing when you first see her. It used to
            // be generated and then thrown away.
            if (generated != null && generated.presence() != null && !generated.presence().isBlank()) {
                CompanionStatus presence = new CompanionStatus();
                presence.setUserId(userId);
                presence.setRoleId(saved.getId());
                presence.setStatusType(CompanionStatus.StatusType.resting);
                presence.setSummary(generated.presence().trim());
                presence.setSummaryAuthored(true);
                statusRepository.save(presence);
            }
            return saved;
        });
    }

    /**
     * Which model a guest wears when they walk in.
     *
     * A model the user chose is either worn or refused with a 400 — never
     * quietly swapped for another one. With no choice, the catalogue picks one
     * that none of this user's visible characters is wearing, reusing one at
     * random when they are all taken. The choice is made among the shared models
     * and the ones this account imported itself. Null only when there is no stage-ready
     * model, in which case the guest is invited as text only.
     *
     * "Worn" is compared by canonical URL, so a character stored with the bare
     * name "a.vrm" and one stored with "/models/a.vrm" count as the same model.
     * A character whose conversation was deleted is off the seat list and does
     * not hold its model.
     */
    private CompanionModelAssetCatalog.ModelAsset resolveGuestModel(Long userId, String requestedModelUrl) {
        if (requestedModelUrl != null && !requestedModelUrl.isBlank()) {
            String ownId = UserAssetService.ownModelId(requestedModelUrl);
            if (ownId == null) return modelAssetCatalog.requireStageAsset(requestedModelUrl);
            // A model from this account's own library. What makes it wearable is
            // that the library holds it for this user; another account's id, or one
            // whose file is gone, is refused like any other missing model.
            try {
                CompanionModelAssetCatalog.ModelAsset own = ownAsset(userAssetService.ownModel(userId, ownId));
                if (own == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CompanionModelAssetCatalog.MODEL_NOT_FOUND);
                return own;
            } catch (ResponseStatusException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CompanionModelAssetCatalog.MODEL_NOT_FOUND);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, CompanionModelAssetCatalog.CATALOG_UNAVAILABLE);
            }
        }
        Set<String> inUse = new HashSet<>();
        for (CompanionRole role : roleRepository.findByUserIdOrderByIdAsc(userId)) {
            if (role.isConversationHidden()) continue;
            String url = modelAssetCatalog.canonicalUrl(role.getModelUrl());
            if (url == null && UserAssetService.ownModelId(role.getModelUrl()) != null) url = role.getModelUrl().trim();
            if (url != null) inUse.add(url);
        }
        List<CompanionModelAssetCatalog.ModelAsset> own = ownStageModels(userId);
        // Nothing of her own: the shared catalogue decides alone, as before.
        return own.isEmpty() ? modelAssetCatalog.pickForGuest(inUse) : modelAssetCatalog.pickForGuest(inUse, own);
    }

    /**
     * The models this account imported into its own library, as assets a guest can
     * wear. The library accepts only self-contained VRM files, so each is stage-ready.
     *
     * @throws ResponseStatusException 500 {@link CompanionModelAssetCatalog#CATALOG_UNAVAILABLE}
     *         when the library cannot be read — never an empty list in its place
     */
    public List<CompanionModelAssetCatalog.ModelAsset> ownStageModels(Long userId) {
        try {
            List<CompanionModelAssetCatalog.ModelAsset> out = new ArrayList<>();
            for (Map<String, Object> entry : userAssetService.listOwnModels(userId)) {
                CompanionModelAssetCatalog.ModelAsset asset = ownAsset(entry);
                if (asset != null) out.add(asset);
            }
            return out;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, CompanionModelAssetCatalog.CATALOG_UNAVAILABLE);
        }
    }

    private static CompanionModelAssetCatalog.ModelAsset ownAsset(Map<String, Object> entry) {
        if (entry == null || !(entry.get("fileUrl") instanceof String url) || UserAssetService.ownModelId(url) == null) return null;
        String name = entry.get("fileName") instanceof String text ? text : "";
        long size = entry.get("size") instanceof Number number ? number.longValue() : 0L;
        return new CompanionModelAssetCatalog.ModelAsset(name, url, size, CompanionModelAssetCatalog.STAGE_TYPE, true);
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

    private TavernSessionDTO toDTO(TavernSession session) {
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
