package com.nexus.chat.service;

import com.nexus.chat.dto.*;
import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import com.nexus.chat.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.function.Consumer;

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
    private final com.nexus.chat.util.ModelEndpointGuard endpointGuard;

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

        List<CompanionRoleDTO> result = new ArrayList<>(roles.size());
        for (CompanionRole role : roles) {
            result.add(toRoleDTO(role, lastAt.get(role.getId()), presence.get(role.getId())));
        }
        return result;
    }

    public CompanionRoleDTO updateRole(Long userId, Long roleId, CompanionRoleDTO update) {
        CompanionRole role = roleRepository.findByIdAndUserId(roleId, userId)
                .orElseThrow(() -> new RuntimeException("role not found"));

        if (update.getName() != null) role.setName(update.getName());
        if (update.getTraits() != null) role.setTraits(JsonUtil.toJson(update.getTraits()));
        if (update.getTone() != null) role.setTone(update.getTone());
        if (update.getBaselineMood() != null) role.setBaselineMood(update.getBaselineMood());
        if (update.getAvatarUrl() != null) role.setAvatarUrl(update.getAvatarUrl());
        if (update.getModelUrl() != null) role.setModelUrl(update.getModelUrl());
        if (update.getModelType() != null) role.setModelType(update.getModelType());
        if (update.getActive() != null) role.setActive(update.getActive());

        roleRepository.save(role);
        return toRoleDTO(role);
    }

    public List<CompanionMessageDTO> getConversation(Long userId, Long roleId) {
        CompanionConversation conversation = getOrCreateConversation(userId, roleId);
        List<CompanionMessage> messages = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        List<CompanionMessageDTO> result = new ArrayList<>();
        for (CompanionMessage msg : messages) {
            result.add(toMessageDTO(msg));
        }
        return result;
    }

    public CompanionChatResponse sendMessage(Long userId, Long roleId, String content) {
        return sendMessage(userId, roleId, content, null);
    }

    public CompanionChatResponse sendMessage(Long userId, Long roleId, String content, String sceneKey) {
        CompanionRole role = roleRepository.findByIdAndUserId(roleId, userId)
                .orElseThrow(() -> new RuntimeException("role not found"));

        CompanionConversation conversation = getOrCreateConversation(userId, roleId);

        CompanionMessage userMessage = new CompanionMessage();
        userMessage.setConversationId(conversation.getId());
        userMessage.setUserId(userId);
        userMessage.setRoleId(roleId);
        userMessage.setSenderType(CompanionMessage.SenderType.user);
        userMessage.setContent(content);
        userMessage.setFallback(false);
        Long sessionId = sessionRepository
                .findFirstByUserIdAndRoleIdAndStatusOrderByStartedAtDesc(
                        userId, roleId, com.nexus.chat.model.TavernSession.Status.active)
                .map(com.nexus.chat.model.TavernSession::getId)
                .orElse(null);
        userMessage.setSessionId(sessionId);
        messageRepository.save(userMessage);

        ResolvedModel resolved = resolveModel(userId, roleId);
        CompanionModelBinding binding = resolved.binding();
        ModelCredential credential = resolved.credential();
        List<CompanionMessage> history = recentHistory(conversation.getId(), binding);

        boolean fallback = false;
        String reply;
        if (resolved.usable()) {
            CompanionModelService.ModelReply modelReply = modelService.generateReply(role, history, binding, credential, sceneKey);
            if (modelReply.success()) {
                reply = modelReply.content();
                credential.setStatus(ModelCredential.CredentialStatus.ok);
            } else {
                reply = fallbackService.generateReply(role, content);
                fallback = true;
                if ("invalid_key".equals(modelReply.error())) {
                    credential.setStatus(ModelCredential.CredentialStatus.invalid);
                }
            }
            credentialRepository.save(credential);
        } else {
            reply = fallbackService.generateReply(role, content);
            fallback = true;
        }

        CompanionMessage roleMessage = new CompanionMessage();
        roleMessage.setConversationId(conversation.getId());
        roleMessage.setUserId(userId);
        roleMessage.setRoleId(roleId);
        roleMessage.setSenderType(CompanionMessage.SenderType.role);
        roleMessage.setContent(reply);
        roleMessage.setFallback(fallback);
        roleMessage.setSessionId(sessionId);
        messageRepository.save(roleMessage);

        updateGrowth(userId, roleId);
        touchConversation(conversation);
        CompanionStatusDTO status = refreshChattingStatus(userId, role);

        return new CompanionChatResponse(
                toMessageDTO(userMessage),
                toMessageDTO(roleMessage),
                fallback,
                status
        );
    }

    /**
     * Streaming variant of {@link #sendMessage}. Same bookkeeping, but the reply
     * reaches the caller as it is written. `onDelta` receives each piece; the
     * returned response is the same shape as the blocking one, so the client can
     * settle on identical state either way.
     */
    public CompanionChatResponse sendMessageStreaming(Long userId,
                                                      Long roleId,
                                                      String content,
                                                      Consumer<String> onDelta) {
        return sendMessageStreaming(userId, roleId, content, null, onDelta);
    }

    public CompanionChatResponse sendMessageStreaming(Long userId,
                                                      Long roleId,
                                                      String content,
                                                      String sceneKey,
                                                      Consumer<String> onDelta) {
        CompanionRole role = roleRepository.findByIdAndUserId(roleId, userId)
                .orElseThrow(() -> new RuntimeException("role not found"));

        CompanionConversation conversation = getOrCreateConversation(userId, roleId);

        CompanionMessage userMessage = new CompanionMessage();
        userMessage.setConversationId(conversation.getId());
        userMessage.setUserId(userId);
        userMessage.setRoleId(roleId);
        userMessage.setSenderType(CompanionMessage.SenderType.user);
        userMessage.setContent(content);
        userMessage.setFallback(false);
        Long sessionId = sessionRepository
                .findFirstByUserIdAndRoleIdAndStatusOrderByStartedAtDesc(
                        userId, roleId, com.nexus.chat.model.TavernSession.Status.active)
                .map(com.nexus.chat.model.TavernSession::getId)
                .orElse(null);
        userMessage.setSessionId(sessionId);
        messageRepository.save(userMessage);

        ResolvedModel resolved = resolveModel(userId, roleId);
        CompanionModelBinding binding = resolved.binding();
        ModelCredential credential = resolved.credential();
        List<CompanionMessage> history = recentHistory(conversation.getId(), binding);

        boolean fallback = false;
        String reply;
        String streamed = null;
        if (resolved.usable()) {
            streamed = modelService.streamReply(role, history, binding, credential, sceneKey, onDelta);
            if (streamed == null) {
                reply = fallbackService.generateReply(role, content);
                fallback = true;
                onDelta.accept(reply);
            } else {
                reply = streamed;
                credential.setStatus(ModelCredential.CredentialStatus.ok);
            }
            credentialRepository.save(credential);
        } else {
            reply = fallbackService.generateReply(role, content);
            fallback = true;
            onDelta.accept(reply);
        }

        CompanionMessage roleMessage = new CompanionMessage();
        roleMessage.setConversationId(conversation.getId());
        roleMessage.setUserId(userId);
        roleMessage.setRoleId(roleId);
        roleMessage.setSenderType(CompanionMessage.SenderType.role);
        roleMessage.setContent(reply);
        roleMessage.setFallback(fallback);
        roleMessage.setSessionId(sessionId);
        messageRepository.save(roleMessage);

        updateGrowth(userId, roleId);
        touchConversation(conversation);
        CompanionStatusDTO status = refreshChattingStatus(userId, role);

        return new CompanionChatResponse(
                toMessageDTO(userMessage),
                toMessageDTO(roleMessage),
                fallback,
                status
        );
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

        ResolvedModel resolved = resolveModel(userId, roleId);
        CompanionModelBinding binding = resolved.binding();
        ModelCredential credential = resolved.credential();
        List<CompanionMessage> history = recentHistory(conversation.getId(), binding);

        String statusSummary = statusRepository.findByUserIdAndRoleId(userId, roleId)
                .map(CompanionStatus::getSummary)
                .orElse(null);
        Integer intimacy = growthRepository.findByUserIdAndRoleId(userId, roleId)
                .map(CompanionGrowth::getIntimacy)
                .orElse(null);

        if (!resolved.usable()) {
            return new CompanionInnerVoiceDTO(roleId, null, false, "no_model_configured");
        }

        CompanionModelService.ModelReply reply = modelService.generateInnerVoice(
                role, history, binding, credential, statusSummary, intimacy);

        if (reply.success()) {
            return new CompanionInnerVoiceDTO(roleId, reply.content(), true, null);
        }
        return new CompanionInnerVoiceDTO(roleId, null, false, reply.error());
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

        if (status.getUpdatedAt() == null ||
                Duration.between(status.getUpdatedAt(), LocalDateTime.now()).toMinutes() > 30) {
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
                .findByUserIdAndProviderAndPurpose(userId, provider, ModelCredential.PURPOSE_CHAT)
                .or(() -> credentialRepository.findByUserIdAndProvider(userId, provider));
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

    public CompanionModelBinding bindModel(Long userId, Long roleId, String provider, String modelName, String endpoint) {
        return bindModel(userId, roleId, provider, modelName, endpoint, null, null, null);
    }

    /**
     * Save the role's model channel, including the sampling knobs the settings
     * panel exposes. Every extra field is optional — null leaves the stored
     * value alone, so an older client that only sends provider/model/endpoint
     * cannot wipe a user's tuned settings.
     */
    public CompanionModelBinding bindModel(Long userId, Long roleId, String provider, String modelName,
                                           String endpoint, Double temperature, Integer personaLock,
                                           Integer contextHorizon) {
        endpointGuard.validate(endpoint);
        CompanionModelBinding binding = modelBindingRepository.findByUserIdAndRoleId(userId, roleId)
                .orElseGet(CompanionModelBinding::new);
        binding.setUserId(userId);
        binding.setRoleId(roleId);
        binding.setProvider(provider);
        binding.setModelName(modelName);
        binding.setEndpoint(endpoint);
        binding.setEnabled(true);
        if (temperature != null) binding.setTemperature(Math.max(0.1, Math.min(1.5, temperature)));
        if (personaLock != null) binding.setPersonaLock(Math.max(50, Math.min(100, personaLock)));
        if (contextHorizon != null) binding.setContextHorizon(contextHorizon);
        return modelBindingRepository.save(binding);
    }

    /**
     * History window for one model call, honoring the binding's contextHorizon
     * token budget. Fetches the newest 100 messages and drops the oldest ones
     * until the estimate fits; the newest message always survives.
     */
    private List<CompanionMessage> recentHistory(Long conversationId, CompanionModelBinding binding) {
        int horizon = binding != null && binding.getContextHorizon() != null && binding.getContextHorizon() > 0
                ? binding.getContextHorizon() : 128_000;
        List<CompanionMessage> history = messageRepository
                .findByConversationIdOrderByCreatedAtDesc(conversationId, PageRequest.of(0, 100));
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
     * Resolve which model a role should talk through.
     *
     * A role binding is the specific answer: the user picked a model for THIS
     * character. But a fresh install has no binding at all, and then the tavern
     * used to silently fall back to canned replies even though the user had
     * already configured a chat provider for the AI assistant. So when there is
     * no binding, borrow the user's default chat credential and its model — same
     * account, same key, one place to configure.
     */
    private ResolvedModel resolveModel(Long userId, Long roleId) {
        CompanionModelBinding binding = modelBindingRepository.findByUserIdAndRoleId(userId, roleId).orElse(null);

        ModelCredential credential = null;
        if (binding != null) {
            credential = credentialRepository
                    .findByUserIdAndProviderAndPurpose(userId, binding.getProvider(), ModelCredential.PURPOSE_CHAT)
                    .or(() -> credentialRepository.findByUserIdAndProvider(userId, binding.getProvider()))
                    .orElse(null);
        }

        if (credential != null && binding != null
                && binding.getEndpoint() != null && !binding.getEndpoint().isBlank()) {
            return new ResolvedModel(binding, credential, null);
        }

        // No usable binding: borrow the default chat provider (and, if the
        // binding had no endpoint of its own, its base URL too).
        Optional<ModelCredential> fallback = credentialRepository
                .findFirstByUserIdAndPurposeAndIsDefaultTrue(userId, ModelCredential.PURPOSE_CHAT);
        if (fallback.isEmpty()) {
            fallback = credentialRepository
                    .findFirstByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(userId, ModelCredential.PURPOSE_CHAT);
        }
        if (fallback.isEmpty()) {
            return new ResolvedModel(binding, null, null);
        }

        ModelCredential borrowed = fallback.get();
        String endpoint = binding != null && binding.getEndpoint() != null && !binding.getEndpoint().isBlank()
                ? binding.getEndpoint()
                : borrowed.getBaseUrl();
        String modelName = binding != null && binding.getModelName() != null && !binding.getModelName().isBlank()
                ? binding.getModelName()
                : borrowed.getDefaultModel();
        String provider = binding != null && binding.getProvider() != null
                ? binding.getProvider()
                : borrowed.getProvider();

        CompanionModelBinding effective = new CompanionModelBinding();
        effective.setUserId(userId);
        effective.setRoleId(roleId);
        effective.setProvider(provider);
        effective.setModelName(modelName);
        effective.setEndpoint(endpoint);
        effective.setEnabled(true);
        return new ResolvedModel(effective, borrowed, null);
    }

    /** The binding to use, the credential to authenticate with, and why it failed. */
    private record ResolvedModel(CompanionModelBinding binding, ModelCredential credential, String reason) {
        boolean usable() {
            return binding != null && credential != null
                    && credential.getApiKeyEncrypted() != null
                    && binding.getEndpoint() != null && !binding.getEndpoint().isBlank();
        }

        /** The first missing piece, named, so the UI can say what to fix. */
        String missing() {
            if (binding == null) return "no_binding";
            if (binding.getEndpoint() == null || binding.getEndpoint().isBlank()) return "no_endpoint";
            if (credential == null) return "no_credential";
            if (credential.getApiKeyEncrypted() == null) return "no_api_key";
            return null;
        }
    }

    /**
     * Send one minimal request through the resolved channel and report what came
     * back. This is what tells "no model configured" apart from "wrong URL",
     * "bad key" and "provider refused".
     */
    public java.util.Map<String, Object> testModelChannel(Long userId, Long roleId) {
        ResolvedModel resolved = resolveModel(userId, roleId);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("usable", resolved.usable());
        out.put("missing", resolved.missing());
        if (!resolved.usable()) return out;

        CompanionModelService.ModelReply probe = modelService.probe(resolved.binding(), resolved.credential());
        out.put("ok", probe.success());
        out.put("error", probe.error());
        out.put("url", modelService.chatCompletionsUrl(resolved.binding().getEndpoint()));
        out.put("model", resolved.binding().getModelName());
        if (probe.success()) {
            out.put("reply", probe.content());
        }
        return out;
    }

    /**
     * What the tavern is actually wired to right now, and — when it cannot reach
     * a model — which piece is missing. Read-only, no model call, so the UI can
     * show this in the settings panel without spending tokens.
     */
    public java.util.Map<String, Object> describeModel(Long userId, Long roleId) {
        ResolvedModel resolved = resolveModel(userId, roleId);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        CompanionModelBinding b = resolved.binding();
        out.put("provider", b != null ? b.getProvider() : null);
        out.put("modelName", b != null ? b.getModelName() : null);
        out.put("endpoint", b != null ? b.getEndpoint() : null);
        out.put("usable", resolved.usable());
        out.put("missing", resolved.missing());
        out.put("hasApiKey", resolved.credential() != null && resolved.credential().getApiKeyEncrypted() != null);
        out.put("credentialStatus", resolved.credential() != null && resolved.credential().getStatus() != null
                ? resolved.credential().getStatus().name() : null);
        // Borrowed means: no per-role binding, using the account's default chat provider.
        out.put("borrowed", b != null && modelBindingRepository
                .findByUserIdAndRoleId(userId, roleId).isEmpty());
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

    private CompanionStatusDTO refreshChattingStatus(Long userId, CompanionRole role) {
        CompanionStatus status = statusRepository.findByUserIdAndRoleId(userId, role.getId())
                .orElseGet(() -> {
                    CompanionStatus s = new CompanionStatus();
                    s.setUserId(userId);
                    s.setRoleId(role.getId());
                    return s;
                });
        status.setStatusType(CompanionStatus.StatusType.chatting);
        status.setSummary(role.getName() + "正在陪你聊天。" );
        statusRepository.save(status);
        return toStatusDTO(status);
    }

    public CompanionRoleDTO toRoleDTO(CompanionRole role) {
        LocalDateTime lastMessageAt = conversationRepository
                .findByUserIdAndRoleId(role.getUserId(), role.getId())
                .map(CompanionConversation::getLastMessageAt)
                .orElse(null);
        String presence = statusRepository
                .findByUserIdAndRoleId(role.getUserId(), role.getId())
                .map(CompanionStatus::getSummary)
                .orElse(null);
        return toRoleDTO(role, lastMessageAt, presence);
    }

    /** Bulk-aware variant — no per-row queries. */
    public CompanionRoleDTO toRoleDTO(CompanionRole role, LocalDateTime lastMessageAt, String presence) {
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
                presence
        );
    }

    private CompanionMessageDTO toMessageDTO(CompanionMessage msg) {
        return new CompanionMessageDTO(
                msg.getId(),
                msg.getRoleId(),
                msg.getSenderType().name(),
                msg.getContent(),
                msg.getFallback(),
                msg.getCreatedAt()
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
                status.getUpdatedAt()
        );
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
