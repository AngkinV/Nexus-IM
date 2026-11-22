package com.nexus.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus.chat.dto.*;
import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import com.nexus.chat.service.CompanionModelService.ModelReply;
import com.nexus.chat.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.nexus.chat.util.CompanionStateTag;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanionService {

    private static final int MAX_GROWTH = 100;

    private final CompanionRoleRepository roleRepository;
    private final CompanionGrowthRepository growthRepository;
    private final CompanionMemoryRepository memoryRepository;
    private final CompanionConversationRepository conversationRepository;
    private final CompanionMessageRepository messageRepository;
    private final CompanionModelBindingRepository modelBindingRepository;
    private final ModelCredentialRepository credentialRepository;
    private final CompanionStatusRepository statusRepository;
    private final CompanionFallbackService fallbackService;
    private final CompanionModelService modelService;
    private final CompanionCryptoService cryptoService;
    private final TavernSessionRepository sessionRepository;
    private final CompanionModelResolver modelResolver;
    private final CompanionModelAssetCatalog modelAssetCatalog;

    public List<CompanionRoleDTO> initDefaultRoles(Long userId) {
        if (roleRepository.countByUserId(userId) > 0) {
            return getRoles(userId);
        }

        List<CompanionRole> roles = new ArrayList<>();
        roles.add(buildRole(userId, "温柔倾听者", List.of("温柔", "慢节奏", "共情强"),
                "柔和、慢速、积极反馈", "安稳"));
        roles.add(buildRole(userId, "理性伙伴", List.of("清晰", "结构化", "执行力"),
                "简洁、清晰、问题导向", "理性平衡"));
        roles.add(buildRole(userId, "活力陪玩", List.of("轻松", "活跃", "鼓励型"),
                "明快、轻松、带一点活力", "活力"));

        roleRepository.saveAll(roles);

        for (CompanionRole role : roles) {
            CompanionGrowth growth = new CompanionGrowth();
            growth.setUserId(userId);
            growth.setRoleId(role.getId());
            growth.setIntimacy(20);
            growth.setTrust(15);
            growth.setStability(80);
            growth.setCoGrowth(10);
            growthRepository.save(growth);

            CompanionStatus status = new CompanionStatus();
            status.setUserId(userId);
            status.setRoleId(role.getId());
            CompanionStatus.StatusType type = fallbackService.pickStatus(LocalDateTime.now());
            status.setStatusType(type);
            status.setSummary(fallbackService.generateStatusSummary(role, type));
            statusRepository.save(status);
        }

        return getRoles(userId);
    }

    public List<CompanionRoleDTO> getRoles(Long userId) {
        List<CompanionRole> roles = roleRepository.findByUserIdOrderByIdAsc(userId);

        // Two bulk reads instead of two queries per character. A seat list with
        // N characters used to cost 2N round trips — the exact reason opening
        // the tavern felt slow when the model was also being called.
        Map<Long, LocalDateTime> lastAt = new HashMap<>();
        for (CompanionConversation conv : conversationRepository.findByUserId(userId)) {
            lastAt.put(conv.getRoleId(), conv.getLastMessageAt());
        }
        Map<Long, String> presence = new HashMap<>();
        for (CompanionStatus status : statusRepository.findByUserId(userId)) {
            presence.put(status.getRoleId(), status.getSummary());
        }
        // The row preview: the newest line per character, one query for the list.
        Map<Long, String> lastLine = new HashMap<>();
        java.util.Set<Long> hasMessages = new java.util.HashSet<>();
        for (CompanionMessage msg : messageRepository.findLatestPerRole(userId)) {
            hasMessages.add(msg.getRoleId());
            if (msg.getCreatedAt() != null) lastAt.put(msg.getRoleId(), msg.getCreatedAt());
            if (msg.getContent() != null && !msg.getContent().isBlank()) {
                lastLine.put(msg.getRoleId(), msg.getContent());
            }
        }

        List<CompanionRoleDTO> result = new ArrayList<>(roles.size());
        for (CompanionRole role : roles) {
            result.add(toRoleDTO(role, hasMessages.contains(role.getId()) ? lastAt.get(role.getId()) : null, presence.get(role.getId()),
                    lastLine.get(role.getId())));
        }
        return result;
    }

    /**
     * Only the profile fields below are writable. lastMessage, lastMessageAt,
     * presence, kind and conversationHidden in the request are ignored: they are
     * derived from history and state, and the response reports the real ones.
     */
    public CompanionRoleDTO updateRole(Long userId, Long roleId, CompanionRoleDTO update) {
        CompanionRole role = roleRepository.findByIdAndUserId(roleId, userId)
                .orElseThrow(() -> new RuntimeException("role not found"));

        String shapeUrl = role.getModelUrl(), shapeType = role.getModelType();
        boolean shapeChanged = update.getModelUrl() != null || update.getModelType() != null;
        if (shapeChanged) {
            String requested = update.getModelUrl() == null ? shapeUrl : update.getModelUrl().trim();
            if (requested == null || requested.isBlank()) {
                shapeUrl = null;
                shapeType = null;
            } else {
                String inferred;
                if (requested.startsWith("http://") || requested.startsWith("https://")) {
                    try {
                        java.net.URI uri = java.net.URI.create(requested);
                        if (uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
                        String path = uri.getPath().toLowerCase(java.util.Locale.ROOT);
                        inferred = path.endsWith(".vrm") ? "vrm" :
                                path.endsWith(".glb") || path.endsWith(".gltf") ? "gltf" :
                                path.endsWith(".fbx") ? "fbx" : null;
                        if (inferred == null) throw new IllegalArgumentException();
                        shapeUrl = uri.toString();
                    } catch (IllegalArgumentException e) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CompanionModelAssetCatalog.MODEL_URL_INVALID);
                    }
                } else {
                    CompanionModelAssetCatalog.ModelAsset asset = modelAssetCatalog.requireAsset(requested);
                    if ("vrm".equals(asset.type()) && !asset.stageReady()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CompanionModelAssetCatalog.MODEL_FILE_INVALID);
                    }
                    shapeUrl = asset.fileUrl();
                    inferred = asset.type();
                }
                String declared = update.getModelType();
                if (declared != null && !declared.isBlank() && !declared.trim().equalsIgnoreCase(inferred)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CompanionModelAssetCatalog.MODEL_TYPE_UNSUPPORTED);
                }
                shapeType = inferred;
            }
        }

        if (update.getName() != null) role.setName(update.getName());
        if (update.getTraits() != null) role.setTraits(JsonUtil.toJson(update.getTraits()));
        if (update.getTone() != null) role.setTone(update.getTone());
        if (update.getBaselineMood() != null) role.setBaselineMood(update.getBaselineMood());
        // Empty string clears the field; null leaves it alone (older clients never send it).
        if (update.getBackground() != null) role.setBackground(blankToNull(update.getBackground()));
        if (update.getGreeting() != null) role.setGreeting(blankToNull(update.getGreeting()));
        if (update.getAvatarUrl() != null) role.setAvatarUrl(update.getAvatarUrl());
        if (shapeChanged) {
            role.setModelUrl(shapeUrl);
            role.setModelType(shapeType);
        }
        if (update.getActive() != null) role.setActive(update.getActive());

        roleRepository.save(role);
        return toRoleDTO(role);
    }

    public List<CompanionMessageDTO> getConversation(Long userId, Long roleId) {
        roleRepository.findByIdAndUserId(roleId, userId)
                .orElseThrow(() -> new RuntimeException("role not found"));
        CompanionConversation conversation = conversationRepository.findByUserIdAndRoleId(userId, roleId).orElse(null);
        if (conversation == null) return List.of();
        List<CompanionMessage> messages = messageRepository
                .findByConversationIdOrderByIdAsc(conversation.getId());
        List<CompanionMessageDTO> result = new ArrayList<>();
        for (CompanionMessage msg : messages) {
            result.add(toMessageDTO(msg));
        }
        return result;
    }

    public CompanionChatResponse sendMessage(Long userId, Long roleId, String content) {
        return exchange(userId, roleId, content, null, null, null, null);
    }

    public CompanionChatResponse sendMessage(Long userId, Long roleId, String content, String sceneKey) {
        return exchange(userId, roleId, content, sceneKey, null, null, null);
    }

    public CompanionChatResponse sendMessage(Long userId, Long roleId, String content,
                                             String sceneKey, String clientMsgId) {
        return exchange(userId, roleId, content, sceneKey, clientMsgId, null, null);
    }

    /**
     * Streaming variant of {@link #sendMessage}. Same bookkeeping, but the reply
     * reaches the caller as it is written.
     *
     * @param onDelta receives each piece of visible text. Throwing from it means
     *                the reader is gone: the model is stopped and what was
     *                written so far is kept.
     * @param onEvent receives side-channel events — "context" (what went into
     *                the prompt), "opening" (her first line of a new evening) and
     *                "state" (what she reports about herself). Must not throw.
     */
    public CompanionChatResponse sendMessageStreaming(Long userId,
                                                      Long roleId,
                                                      String content,
                                                      String sceneKey,
                                                      String clientMsgId,
                                                      Consumer<String> onDelta,
                                                      BiConsumer<String, Object> onEvent) {
        return exchange(userId, roleId, content, sceneKey, clientMsgId, onDelta, onEvent);
    }

    /**
     * One exchange: file the user's line, ask the model, file her answer.
     * Blocking and streaming sends are the same thing with or without a listener,
     * so they cannot drift apart again.
     */
    private CompanionChatResponse exchange(Long userId,
                                           Long roleId,
                                           String content,
                                           String sceneKey,
                                           String clientMsgId,
                                           Consumer<String> onDelta,
                                           BiConsumer<String, Object> onEvent) {
        CompanionRole role = roleRepository.findByIdAndUserId(roleId, userId)
                .orElseThrow(() -> new RuntimeException("role not found"));
        CompanionConversation conversation = getOrCreateConversation(userId, roleId);
        String clientId = normalizeClientId(clientMsgId);

        // ── A retry of something already sent? ─────────────────────────────
        CompanionMessage userMessage = clientId == null ? null : messageRepository
                .findFirstByUserIdAndRoleIdAndClientMsgIdOrderByIdAsc(userId, roleId, clientId)
                .orElse(null);
        if (userMessage != null) {
            CompanionMessage next = messageRepository
                    .findFirstByConversationIdAndIdGreaterThanOrderByIdAsc(conversation.getId(), userMessage.getId())
                    .orElse(null);
            if (next != null && next.getSenderType() == CompanionMessage.SenderType.role) {
                if (!CompanionStateTag.isPartial(next.getStateJson())) {
                    // Already answered: hand back what was stored. No second model
                    // call, no second copy of the line, no second growth tick.
                    if (onDelta != null) {
                        try {
                            onDelta.accept(next.getContent() == null ? "" : next.getContent());
                        } catch (RuntimeException ignored) {
                            // The reader left again; the stored exchange stands.
                        }
                    }
                    CompanionChatResponse replay = new CompanionChatResponse(
                            toMessageDTO(userMessage), toMessageDTO(next),
                            Boolean.TRUE.equals(next.getFallback()), currentStatus(userId, roleId));
                    replay.setSessionId(next.getSessionId());
                    return replay;
                }
                // The stored answer was cut short, so the retry is a real one:
                // drop the fragment and ask the model again under the same line.
                messageRepository.delete(next);
            }
        }

        // ── The evening ────────────────────────────────────────────────────
        // An evening opens when the first line is said, not when the room is
        // looked at — browsing characters must not leave empty sessions behind.
        TavernSession session = sessionRepository
                .findFirstByUserIdAndRoleIdAndStatusOrderByStartedAtDesc(userId, roleId, TavernSession.Status.active)
                .orElse(null);
        CompanionMessage opening = null;
        if (session == null) {
            session = new TavernSession();
            session.setUserId(userId);
            session.setRoleId(roleId);
            session.setSceneKey(sceneKey);
            session.setStatus(TavernSession.Status.active);
            session.setStartedAt(LocalDateTime.now());
            session = sessionRepository.save(session);

            if (role.getGreeting() != null && !role.getGreeting().isBlank()) {
                opening = new CompanionMessage();
                opening.setConversationId(conversation.getId());
                opening.setUserId(userId);
                opening.setRoleId(roleId);
                opening.setSenderType(CompanionMessage.SenderType.role);
                opening.setContent(role.getGreeting().trim());
                opening.setFallback(false);
                opening.setSessionId(session.getId());
                messageRepository.save(opening);
            }
        } else if (sceneKey != null && !sceneKey.equals(session.getSceneKey())) {
            session.setSceneKey(sceneKey);
            sessionRepository.save(session);
        }
        String scene = session.getSceneKey();

        if (userMessage == null) {
            userMessage = new CompanionMessage();
            userMessage.setConversationId(conversation.getId());
            userMessage.setUserId(userId);
            userMessage.setRoleId(roleId);
            userMessage.setSenderType(CompanionMessage.SenderType.user);
            userMessage.setContent(content);
            userMessage.setFallback(false);
            userMessage.setClientMsgId(clientId);
            userMessage.setSessionId(session.getId());
            messageRepository.save(userMessage);
        } else if (!session.getId().equals(userMessage.getSessionId())) {
            // A retry after its evening was closed: the line moves to tonight.
            userMessage.setSessionId(session.getId());
            messageRepository.save(userMessage);
        }

        if (role.isConversationHidden()) {
            role.setConversationHidden(false);
            roleRepository.save(role);
        }

        // ── What she is told ───────────────────────────────────────────────
        CompanionModelResolver.Resolved resolved = modelResolver.resolve(userId, roleId);
        CompanionModelBinding binding = resolved.binding();
        ModelCredential credential = resolved.credential();
        List<CompanionMessage> history = sessionHistory(session.getId(), binding);
        MemoryPick memories = pickMemories(userId, roleId);
        CompanionPromptBuilder.Context ctx = replyContext(userId, role, scene, binding, memories);

        if (onEvent != null) {
            Map<String, Object> context = new java.util.LinkedHashMap<>();
            context.put("sessionId", session.getId());
            context.put("sceneKey", scene);
            context.put("memoryIds", memories.ids());
            context.put("historyCount", history.size());
            context.put("model", resolved.usable() ? binding.getModelName() : null);
            onEvent.accept("context", context);
            if (opening != null) onEvent.accept("opening", toMessageDTO(opening));
        }

        // ── Her answer ─────────────────────────────────────────────────────
        String reply = null;
        CompanionStateTag.State state = null;
        boolean fallback = !resolved.configured();
        boolean readerLeft = false;
        JsonNode modelError = null;
        if (fallback) {
            reply = fallbackService.generateReply(role, content);
            if (onDelta != null) {
                try {
                    onDelta.accept(reply);
                } catch (RuntimeException ignored) {
                    // The offline line is already complete; it can still be read from history.
                }
            }
        } else if (!resolved.usable()) {
            modelError = ModelReply.configuration(resolved.missing()).error();
        } else {
            ModelReply outcome = null;
            if (onDelta == null) {
                outcome = modelService.generateReply(ctx, history, binding, credential);
                CompanionStateTag.Parsed parsed = CompanionStateTag.parse(outcome.content());
                reply = parsed.text();
                state = parsed.state();
            } else {
                CompanionStateTag.Filter filter = new CompanionStateTag.Filter(piece -> {
                    try {
                        onDelta.accept(piece);
                    } catch (RuntimeException disconnected) {
                        throw new CompanionModelService.ReaderGone();
                    }
                });
                AtomicBoolean stateSent = new AtomicBoolean(false);
                Runnable sendState = () -> {
                    if (onEvent != null && filter.state() != null && stateSent.compareAndSet(false, true)) {
                        onEvent.accept("state", filter.state().toMap());
                    }
                };
                try {
                    outcome = modelService.streamReply(ctx, history, binding, credential, piece -> {
                        filter.accept(piece);
                        sendState.run();
                    });
                    filter.finish();
                    sendState.run();
                } catch (CompanionModelService.ReaderGone disconnected) {
                    readerLeft = true;
                }
                // Only count deltas actually delivered, not raw aggregate text buffered by the gateway.
                reply = filter.visibleText();
                state = filter.state();
            }
            if (!readerLeft) {
                modelError = outcome.error();
                if (modelError == null && (reply == null || reply.isBlank())) modelError = ModelReply.emptyContent().error();
            }
        }

        boolean hasReply = reply != null && !reply.isBlank();
        boolean partial = readerLeft || (modelError != null && hasReply);
        boolean completed = hasReply && !partial && modelError == null;
        CompanionMessage roleMessage = null;
        if (hasReply) {
            roleMessage = new CompanionMessage();
            roleMessage.setConversationId(conversation.getId());
            roleMessage.setUserId(userId);
            roleMessage.setRoleId(roleId);
            roleMessage.setSenderType(CompanionMessage.SenderType.role);
            roleMessage.setContent(reply);
            roleMessage.setFallback(fallback);
            roleMessage.setSessionId(session.getId());
            roleMessage.setStateJson(CompanionStateTag.toJson(state, partial));
            messageRepository.save(roleMessage);
        }
        if (completed && !fallback) updateGrowth(userId, roleId);
        touchConversation(conversation); // the user's line is still part of the history on failure
        CompanionStatusDTO status = completed ? recordState(userId, role, state) : currentStatus(userId, roleId);
        CompanionChatResponse response = new CompanionChatResponse(toMessageDTO(userMessage),
                roleMessage == null ? null : toMessageDTO(roleMessage), fallback, status);
        response.setOpeningMessage(opening == null ? null : toMessageDTO(opening));
        response.setSessionId(session.getId());
        response.setSceneKey(scene);
        response.setMemoryIds(fallback ? List.of() : memories.ids());
        response.setPartial(partial);
        response.setModelError(modelError);
        return response;
    }

    private static String normalizeClientId(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private CompanionPromptBuilder.Context replyContext(Long userId, CompanionRole role, String scene,
                                                         CompanionModelBinding binding, MemoryPick memories) {
        CompanionGrowth growth = growthRepository.findByUserIdAndRoleId(userId, role.getId()).orElse(null);
        return new CompanionPromptBuilder.Context(role, scene, binding == null ? null : binding.getPersonaLock(),
                growth == null ? null : growth.getIntimacy(), growth == null ? null : growth.getTrust(),
                memories.lines(), true);
    }

    /** Which memories go in front of the model, and the ids to report back. */
    private record MemoryPick(List<Long> ids, List<String> lines) {}

    private static final int MEMORY_CONFIRMED_MAX = 8;
    private static final int MEMORY_RECENT_MAX = 4;
    private static final int MEMORY_CHAR_BUDGET = 1200;

    /**
     * Memories for the prompt. Confirmed ones are the user saying "keep this", so
     * they always go in (newest first, up to a cap); of the rest only the most
     * recent few do. Deleting a memory is therefore enough to make her forget it.
     */
    private MemoryPick pickMemories(Long userId, Long roleId) {
        List<CompanionMemory> all = memoryRepository.findByUserIdAndRoleIdOrderByCreatedAtDesc(userId, roleId);
        List<CompanionMemory> chosen = new ArrayList<>();
        int confirmed = 0;
        int recent = 0;
        for (CompanionMemory memory : all) {
            if (memory.getContent() == null || memory.getContent().isBlank()) continue;
            if (Boolean.TRUE.equals(memory.getConfirmed())) {
                if (confirmed++ < MEMORY_CONFIRMED_MAX) chosen.add(memory);
            } else if (recent++ < MEMORY_RECENT_MAX) {
                chosen.add(memory);
            }
        }
        // Confirmed first: if the budget runs out, the unpinned ones are what drop.
        chosen.sort((a, b) -> Boolean.compare(Boolean.TRUE.equals(b.getConfirmed()), Boolean.TRUE.equals(a.getConfirmed())));

        List<Long> ids = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        int used = 0;
        for (CompanionMemory memory : chosen) {
            String text = memory.getContent().trim();
            if (used + text.length() > MEMORY_CHAR_BUDGET) continue;
            used += text.length();
            ids.add(memory.getId());
            lines.add(text);
        }
        return new MemoryPick(ids, lines);
    }

    /**
     * Her inner monologue. Costs a model call, so the client asks for it only
     * when the user opens the whisper layer. Without a configured model the
     * answer says so instead of inventing a thought.
     */
    public CompanionInnerVoiceDTO generateInnerVoice(Long userId, Long roleId) {
        CompanionRole role = roleRepository.findByIdAndUserId(roleId, userId)
                .orElseThrow(() -> new RuntimeException("role not found"));

        CompanionConversation conversation = getOrCreateConversation(userId, roleId);

        CompanionModelResolver.Resolved resolved = modelResolver.resolve(userId, roleId);
        CompanionModelBinding binding = resolved.binding();
        ModelCredential credential = resolved.credential();
        // Tonight's conversation if there is one; otherwise the tail of the last.
        List<CompanionMessage> history = sessionRepository
                .findFirstByUserIdAndRoleIdAndStatusOrderByStartedAtDesc(userId, roleId, TavernSession.Status.active)
                .map(active -> sessionHistory(active.getId(), binding))
                .orElseGet(() -> {
                    List<CompanionMessage> tail = new ArrayList<>(messageRepository
                            .findByConversationIdOrderByCreatedAtDesc(conversation.getId(), PageRequest.of(0, 12)));
                    Collections.reverse(tail);
                    return tail;
                });

        String statusSummary = statusRepository.findByUserIdAndRoleId(userId, roleId)
                .map(CompanionStatus::getSummary)
                .orElse(null);
        Integer intimacy = growthRepository.findByUserIdAndRoleId(userId, roleId)
                .map(CompanionGrowth::getIntimacy)
                .orElse(null);

        if (!resolved.usable()) {
            return new CompanionInnerVoiceDTO(roleId, null, false,
                    resolved.configured() ? resolved.missing() : "no_model_configured");
        }

        ModelReply reply = modelService.generateInnerVoice(
                role, history, binding, credential, statusSummary, intimacy);

        if (reply.success()) {
            return new CompanionInnerVoiceDTO(roleId, reply.content(), true, null);
        }
        return new CompanionInnerVoiceDTO(roleId, null, false, reply.error().path("summary").asText());
    }

    public List<CompanionMemoryDTO> getMemories(Long userId, Long roleId) {
        List<CompanionMemory> memories = memoryRepository.findByUserIdAndRoleIdOrderByCreatedAtDesc(userId, roleId);
        List<CompanionMemoryDTO> result = new ArrayList<>();
        for (CompanionMemory memory : memories) {
            result.add(toMemoryDTO(memory));
        }
        return result;
    }

    public CompanionMemoryDTO createMemory(Long userId, Long roleId, String type, String content) {
        CompanionMemory memory = new CompanionMemory();
        memory.setUserId(userId);
        memory.setRoleId(roleId);
        memory.setMemoryType(parseMemoryType(type));
        memory.setContent(content);
        memory.setConfirmed(false);
        memory.setShared(false);
        memoryRepository.save(memory);
        return toMemoryDTO(memory);
    }

    public CompanionMemoryDTO confirmMemory(Long userId, Long memoryId) {
        CompanionMemory memory = memoryRepository.findByIdAndUserId(memoryId, userId)
                .orElseThrow(() -> new RuntimeException("memory not found"));
        memory.setConfirmed(true);
        memoryRepository.save(memory);
        return toMemoryDTO(memory);
    }

    public void deleteMemory(Long userId, Long memoryId) {
        CompanionMemory memory = memoryRepository.findByIdAndUserId(memoryId, userId)
                .orElseThrow(() -> new RuntimeException("memory not found"));
        memoryRepository.delete(memory);
    }

    public void clearMemories(Long userId, Long roleId) {
        memoryRepository.deleteByUserIdAndRoleId(userId, roleId);
    }

    public CompanionGrowthDTO getGrowth(Long userId, Long roleId) {
        CompanionGrowth growth = growthRepository.findByUserIdAndRoleId(userId, roleId)
                .orElseGet(() -> {
                    CompanionGrowth g = new CompanionGrowth();
                    g.setUserId(userId);
                    g.setRoleId(roleId);
                    g.setIntimacy(0);
                    g.setTrust(0);
                    g.setStability(80);
                    g.setCoGrowth(0);
                    return growthRepository.save(g);
                });
        return toGrowthDTO(growth);
    }

    public CompanionStatusDTO getStatus(Long userId, Long roleId) {
        CompanionRole role = roleRepository.findByIdAndUserId(roleId, userId)
                .orElseThrow(() -> new RuntimeException("role not found"));
        CompanionStatus status = statusRepository.findByUserIdAndRoleId(userId, roleId)
                .orElseGet(() -> {
                    CompanionStatus s = new CompanionStatus();
                    s.setUserId(userId);
                    s.setRoleId(roleId);
                    CompanionStatus.StatusType type = fallbackService.pickStatus(LocalDateTime.now());
                    s.setStatusType(type);
                    s.setSummary(fallbackService.generateStatusSummary(role, type));
                    return statusRepository.save(s);
                });

        // The clock-based line is a placeholder for a character no model has
        // described yet. Once a line is hers — she reported it, or it came with
        // her as an invited guest — it stands until she reports another.
        if (!Boolean.TRUE.equals(status.getSummaryAuthored()) && (status.getUpdatedAt() == null ||
                Duration.between(status.getUpdatedAt(), LocalDateTime.now()).toMinutes() > 30)) {
            CompanionStatus.StatusType type = fallbackService.pickStatus(LocalDateTime.now());
            status.setStatusType(type);
            status.setSummary(fallbackService.generateStatusSummary(role, type));
            statusRepository.save(status);
        }

        return toStatusDTO(status);
    }

    public CompanionStatusDTO updateStatus(Long userId, Long roleId, String statusType, String summary) {
        CompanionRole role = roleRepository.findByIdAndUserId(roleId, userId)
                .orElseThrow(() -> new RuntimeException("role not found"));
        CompanionStatus status = statusRepository.findByUserIdAndRoleId(userId, roleId)
                .orElseGet(() -> {
                    CompanionStatus s = new CompanionStatus();
                    s.setUserId(userId);
                    s.setRoleId(roleId);
                    return s;
                });
        status.setStatusType(parseStatusType(statusType));
        status.setSummary(summary != null ? summary : fallbackService.generateStatusSummary(role, status.getStatusType()));
        statusRepository.save(status);
        return toStatusDTO(status);
    }

    public ModelCredentialStatusDTO saveCredential(Long userId, String provider, String apiKey) {
        ModelCredential credential = credentialRepository
                .findByUserIdAndProviderAndPurpose(userId, provider, ModelCredential.PURPOSE_CHAT)
                .orElseGet(ModelCredential::new);
        credential.setUserId(userId);
        credential.setProvider(provider);
        credential.setPurpose(ModelCredential.PURPOSE_CHAT);
        credential.setApiKeyEncrypted(cryptoService.encrypt(apiKey));
        credential.setStatus(ModelCredential.CredentialStatus.unknown);
        credentialRepository.save(credential);

        return new ModelCredentialStatusDTO(
                credential.getProvider(),
                credential.getStatus().name(),
                maskKey(apiKey),
                credential.getUpdatedAt()
        );
    }

    public ModelCredentialStatusDTO getCredentialStatus(Long userId, String provider) {
        Optional<ModelCredential> optional = credentialRepository
                .findByUserIdAndProviderAndPurpose(userId, provider, ModelCredential.PURPOSE_CHAT);
        if (optional.isEmpty()) {
            return new ModelCredentialStatusDTO(provider, "missing", null, null);
        }
        ModelCredential credential = optional.get();
        String masked = maskEncryptedKey(credential.getApiKeyEncrypted());
        return new ModelCredentialStatusDTO(
                credential.getProvider(),
                credential.getStatus().name(),
                masked,
                credential.getUpdatedAt()
        );
    }

    /** Select a saved chat channel. An absent override tracks that channel's current model. */
    public Map<String, Object> bindModel(Long userId, Long roleId, CompanionModelBindingRequest request) {
        requireModelRole(userId, roleId);
        if (request == null || request.credentialId() == null || request.credentialId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "credentialId is required");
        }
        ModelCredential credential = credentialRepository.findByIdAndUserId(request.credentialId(), userId)
                .filter(c -> ModelCredential.PURPOSE_CHAT.equals(c.getPurpose()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "chat credential not found"));
        String model = blankToNull(request.modelName());
        if (model != null && (model.length() > 100 || model.chars().anyMatch(Character::isISOControl))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid role model name");
        }
        if (request.temperature() != null && !Double.isFinite(request.temperature())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid temperature");
        }
        CompanionModelBinding binding = modelBindingRepository.findByUserIdAndRoleId(userId, roleId)
                .orElseGet(CompanionModelBinding::new);
        binding.setUserId(userId);
        binding.setRoleId(roleId);
        binding.setProvider(credential.getProvider()); // unique per owner and purpose
        binding.setEndpoint(null); // track credential edits; never persist a second endpoint
        binding.setModelName(model);
        if (request.enabled() != null) binding.setEnabled(request.enabled());
        if (request.temperature() != null) binding.setTemperature(Math.max(0.1, Math.min(1.5, request.temperature())));
        if (request.personaLock() != null) binding.setPersonaLock(Math.max(50, Math.min(100, request.personaLock())));
        if (request.contextHorizon() != null) binding.setContextHorizon(Math.max(32_000, Math.min(128_000, request.contextHorizon())));
        modelBindingRepository.save(binding);
        return modelStatus(modelResolver.resolve(userId, roleId));
    }

    private CompanionRole requireModelRole(Long userId, Long roleId) {
        return roleRepository.findByIdAndUserId(roleId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "role not found"));
    }

    /**
     * Tonight's conversation, for one model call. Only the open evening: a closed
     * one reaches her through the sentence it left in memory, not as raw turns —
     * that is what makes closing an evening mean something.
     *
     * Honors the binding's contextHorizon token budget: takes the newest 100
     * messages and drops the oldest until the estimate fits; the newest survives.
     */
    private List<CompanionMessage> sessionHistory(Long sessionId, CompanionModelBinding binding) {
        int horizon = binding != null && binding.getContextHorizon() != null && binding.getContextHorizon() > 0
                ? binding.getContextHorizon() : 128_000;
        List<CompanionMessage> history = new ArrayList<>(messageRepository
                .findBySessionIdOrderByIdDesc(sessionId, PageRequest.of(0, 100)));
        Collections.reverse(history);
        int total = 0;
        for (CompanionMessage m : history) total += estimateTokens(m.getContent());
        int first = 0;
        while (total > horizon && first < history.size() - 1) {
            total -= estimateTokens(history.get(first).getContent());
            first++;
        }
        return first == 0 ? history : new ArrayList<>(history.subList(first, history.size()));
    }

    /** Rough token estimate: 1 per CJK char, ~4 chars per token otherwise. No tokenizer dependency. */
    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0, other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) cjk++;
            else other++;
        }
        return cjk + (other + 3) / 4;
    }

    private CompanionRole buildRole(Long userId, String name, List<String> traits, String tone, String baselineMood) {
        CompanionRole role = new CompanionRole();
        role.setUserId(userId);
        role.setName(name);
        role.setTraits(JsonUtil.toJson(traits));
        role.setTone(tone);
        role.setBaselineMood(baselineMood);
        role.setActive(true);
        return role;
    }

    /**
     * Send one minimal request through the resolved channel and report what came
     * back. This is what tells "no model configured" apart from "wrong URL",
     * "bad key" and "provider refused".
     */
    public java.util.Map<String, Object> testModelChannel(Long userId, Long roleId) {
        CompanionRole role = requireModelRole(userId, roleId);
        CompanionModelResolver.Resolved resolved = modelResolver.resolve(userId, roleId);
        Map<String, Object> out = modelStatus(resolved);
        if (!resolved.usable()) {
            out.put("ok", false);
            out.put("error", ModelReply.configuration(resolved.missing()).error());
            return out;
        }
        var session = sessionRepository.findFirstByUserIdAndRoleIdAndStatusOrderByStartedAtDesc(userId, roleId, TavernSession.Status.active);
        List<CompanionMessage> history = new ArrayList<>(session.map(s -> sessionHistory(s.getId(), resolved.binding())).orElse(List.of()));
        CompanionMessage sample = new CompanionMessage();
        sample.setSenderType(CompanionMessage.SenderType.user);
        sample.setContent("你好，请简短回应一句。");
        history.add(sample);
        var ctx = replyContext(userId, role, session.map(TavernSession::getSceneKey).orElse(null), resolved.binding(), pickMemories(userId, roleId));
        ModelReply probe = modelService.probe(ctx, history, resolved.binding(), resolved.credential());
        out.put("ok", probe.success());
        out.put("error", probe.error());
        if (probe.success()) out.put("reply", probe.content());
        return out;
    }

    /**
     * What the tavern is actually wired to right now, and — when it cannot reach
     * a model — which piece is missing. Read-only, no model call, so the UI can
     * show this in the settings panel without spending tokens.
     */
    public Map<String, Object> describeModel(Long userId, Long roleId) {
        requireModelRole(userId, roleId);
        return modelStatus(modelResolver.resolve(userId, roleId));
    }

    private Map<String, Object> modelStatus(CompanionModelResolver.Resolved resolved) {
        Map<String, Object> out = new LinkedHashMap<>();
        CompanionModelBinding b = resolved.binding();
        ModelCredential credential = resolved.credential();
        out.put("credentialId", credential == null ? null : credential.getId());
        out.put("defaultModel", credential == null ? null : credential.getDefaultModel());
        out.put("explicitModelName", resolved.explicitModelName());
        out.put("enabled", b == null || Boolean.TRUE.equals(b.getEnabled()));
        out.put("temperature", CompanionModelService.temperatureOf(b));
        out.put("personaLock", b == null || b.getPersonaLock() == null ? 92 : b.getPersonaLock());
        out.put("contextHorizon", b == null || b.getContextHorizon() == null ? 128000 : b.getContextHorizon());
        out.put("provider", b != null ? b.getProvider() : null);
        out.put("modelName", b != null ? b.getModelName() : null);
        out.put("endpoint", b != null ? b.getEndpoint() : null);
        out.put("usable", resolved.usable());
        out.put("missing", resolved.missing());
        out.put("hasApiKey", resolved.credential() != null && resolved.credential().getApiKeyEncrypted() != null);
        out.put("credentialStatus", resolved.credential() != null && resolved.credential().getStatus() != null
                ? resolved.credential().getStatus().name() : null);
        // Borrowed means: no per-role binding, using the account's default chat provider.
        out.put("borrowed", b != null && resolved.borrowed());
        return out;
    }

    private CompanionConversation getOrCreateConversation(Long userId, Long roleId) {
        return conversationRepository.findByUserIdAndRoleId(userId, roleId)
                .orElseGet(() -> {
                    CompanionConversation conv = new CompanionConversation();
                    conv.setUserId(userId);
                    conv.setRoleId(roleId);
                    return conversationRepository.save(conv);
                });
    }

    /**
     * Stamp the conversation with the time of this exchange. This is what the
     * seat list sorts by, so a character rises to the top because you talked to
     * them, never because you merely selected them.
     */
    private void touchConversation(CompanionConversation conversation) {
        conversation.setLastMessageAt(LocalDateTime.now());
        conversationRepository.save(conversation);
    }

    private void updateGrowth(Long userId, Long roleId) {
        CompanionGrowth growth = growthRepository.findByUserIdAndRoleId(userId, roleId)
                .orElseGet(() -> {
                    CompanionGrowth g = new CompanionGrowth();
                    g.setUserId(userId);
                    g.setRoleId(roleId);
                    g.setIntimacy(0);
                    g.setTrust(0);
                    g.setStability(80);
                    g.setCoGrowth(0);
                    return g;
                });

        growth.setIntimacy(Math.min(MAX_GROWTH, growth.getIntimacy() + 1));
        growth.setTrust(Math.min(MAX_GROWTH, growth.getTrust() + 1));
        growth.setCoGrowth(Math.min(MAX_GROWTH, growth.getCoGrowth() + 1));
        growthRepository.save(growth);
    }

    /**
     * After an exchange: she is talking to you, and if she reported a state, that
     * is now her state. Without a report nothing about her mood is written — the
     * previous one stands rather than a guess replacing it. Gestures are cues
     * for one reply only: an omitted gesture clears the previous cue.
     */
    private CompanionStatusDTO recordState(Long userId, CompanionRole role, CompanionStateTag.State state) {
        CompanionStatus status = statusRepository.findByUserIdAndRoleId(userId, role.getId())
                .orElseGet(() -> {
                    CompanionStatus s = new CompanionStatus();
                    s.setUserId(userId);
                    s.setRoleId(role.getId());
                    return s;
                });
        status.setStatusType(CompanionStatus.StatusType.chatting);
        status.setGesture(state == null ? null : state.gesture());
        if (state != null) {
            if (state.mood() != null) status.setMood(state.mood());
            if (state.motion() != null) status.setMotion(state.motion());
            if (state.doing() != null) {
                status.setSummary(state.doing());
                status.setSummaryAuthored(true);
            }
        } else if (!Boolean.TRUE.equals(status.getSummaryAuthored())) {
            status.setSummary(role.getName() + "正在陪你聊天。");
        }
        statusRepository.save(status);
        return toStatusDTO(status);
    }

    /** Her status as stored, without the clock refresh — null if none exists yet. */
    private CompanionStatusDTO currentStatus(Long userId, Long roleId) {
        return statusRepository.findByUserIdAndRoleId(userId, roleId).map(this::toStatusDTO).orElse(null);
    }

    /** One character with its real recency, presence and last line — three single-row reads. */
    public CompanionRoleDTO toRoleDTO(CompanionRole role) {
        LocalDateTime lastMessageAt = conversationRepository
                .findByUserIdAndRoleId(role.getUserId(), role.getId())
                .map(CompanionConversation::getLastMessageAt)
                .orElse(null);
        String presence = statusRepository
                .findByUserIdAndRoleId(role.getUserId(), role.getId())
                .map(CompanionStatus::getSummary)
                .orElse(null);
        // The same preview getRoles() reports: the newest message by id, and no
        // preview when that message is blank. A response without it would wipe
        // the row's last line in the client after a settings change.
        Optional<CompanionMessage> latest = messageRepository
                .findFirstByUserIdAndRoleIdOrderByIdDesc(role.getUserId(), role.getId());
        String lastMessage = latest.map(CompanionMessage::getContent)
                .filter(content -> !content.isBlank()).orElse(null);
        if (latest.isEmpty()) lastMessageAt = null;
        else if (latest.get().getCreatedAt() != null) lastMessageAt = latest.get().getCreatedAt();
        return toRoleDTO(role, lastMessageAt, presence, lastMessage);
    }

    /** Bulk-aware variant — no per-row queries; the caller supplies the preview. */
    public CompanionRoleDTO toRoleDTO(CompanionRole role, LocalDateTime lastMessageAt, String presence,
                                      String lastMessage) {
        return new CompanionRoleDTO(
                role.getId(),
                role.getName(),
                JsonUtil.toStringList(role.getTraits()),
                role.getTone(),
                role.getBaselineMood(),
                role.getAvatarUrl(),
                role.getModelUrl(),
                role.getModelType(),
                role.getActive(),
                role.getKind() == null ? CompanionRole.Kind.regular.name() : role.getKind().name(),
                lastMessageAt,
                presence,
                lastMessage,
                role.getBackground(),
                role.getGreeting(),
                role.isConversationHidden()
        );
    }

    private CompanionMessageDTO toMessageDTO(CompanionMessage msg) {
        return new CompanionMessageDTO(
                msg.getId(),
                msg.getRoleId(),
                msg.getSenderType().name(),
                msg.getContent(),
                msg.getFallback(),
                msg.getCreatedAt(),
                msg.getSessionId(),
                stateMap(msg.getStateJson()),
                msg.getClientMsgId(),
                CompanionStateTag.isPartial(msg.getStateJson()) ? Boolean.TRUE : null
        );
    }

    private CompanionMemoryDTO toMemoryDTO(CompanionMemory memory) {
        return new CompanionMemoryDTO(
                memory.getId(),
                memory.getRoleId(),
                memory.getMemoryType().name(),
                memory.getContent(),
                memory.getConfirmed(),
                memory.getShared(),
                memory.getCreatedAt()
        );
    }

    private CompanionGrowthDTO toGrowthDTO(CompanionGrowth growth) {
        return new CompanionGrowthDTO(
                growth.getRoleId(),
                growth.getIntimacy(),
                growth.getTrust(),
                growth.getStability(),
                growth.getCoGrowth(),
                growth.getUpdatedAt()
        );
    }

    private CompanionStatusDTO toStatusDTO(CompanionStatus status) {
        return new CompanionStatusDTO(
                status.getRoleId(),
                status.getStatusType().name(),
                status.getSummary(),
                status.getUpdatedAt(),
                status.getMood(),
                status.getMotion(),
                status.getGesture()
        );
    }

    private static Map<String, String> stateMap(String stateJson) {
        CompanionStateTag.State state = CompanionStateTag.fromJson(stateJson);
        return state == null ? null : state.toMap();
    }

    private CompanionMemory.MemoryType parseMemoryType(String type) {
        if (type == null) return CompanionMemory.MemoryType.mid;
        return switch (type) {
            case "short", "short_term" -> CompanionMemory.MemoryType.short_term;
            case "long", "long_term" -> CompanionMemory.MemoryType.long_term;
            default -> CompanionMemory.MemoryType.mid;
        };
    }

    private CompanionStatus.StatusType parseStatusType(String type) {
        if (type == null) return CompanionStatus.StatusType.resting;
        try {
            return CompanionStatus.StatusType.valueOf(type);
        } catch (Exception e) {
            return CompanionStatus.StatusType.resting;
        }
    }

    private String maskEncryptedKey(String encrypted) {
        if (encrypted == null) return null;
        try {
            String plain = cryptoService.decrypt(encrypted);
            return maskKey(plain);
        } catch (Exception e) {
            return "***";
        }
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8) return "***";
        return key.substring(0, 3) + "****" + key.substring(key.length() - 3);
    }
}
