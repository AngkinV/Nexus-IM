package com.nexus.chat.service;

import com.nexus.chat.dto.*;
import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import com.nexus.chat.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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
        List<CompanionRoleDTO> result = new ArrayList<>();
        for (CompanionRole role : roles) {
            result.add(toRoleDTO(role));
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
        messageRepository.save(userMessage);

        List<CompanionMessage> history = messageRepository
                .findTop20ByConversationIdOrderByCreatedAtDesc(conversation.getId());
        Collections.reverse(history);

        CompanionModelBinding binding = modelBindingRepository.findByUserIdAndRoleId(userId, roleId).orElse(null);
        ModelCredential credential = null;
        if (binding != null) {
            // Companion is purely chat — explicitly scope the lookup so a user who also has
            // an embedding-purpose row under the same provider id can't have it returned here.
            credential = credentialRepository
                    .findByUserIdAndProviderAndPurpose(userId, binding.getProvider(), ModelCredential.PURPOSE_CHAT)
                    .or(() -> credentialRepository.findByUserIdAndProvider(userId, binding.getProvider()))
                    .orElse(null);
        }

        boolean fallback = false;
        String reply;
        if (binding != null && credential != null && credential.getApiKeyEncrypted() != null) {
            CompanionModelService.ModelReply modelReply = modelService.generateReply(role, history, binding, credential);
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
        messageRepository.save(roleMessage);

        updateGrowth(userId, roleId);
        CompanionStatusDTO status = refreshChattingStatus(userId, role);

        return new CompanionChatResponse(
                toMessageDTO(userMessage),
                toMessageDTO(roleMessage),
                fallback,
                status
        );
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
        CompanionModelBinding binding = modelBindingRepository.findByUserIdAndRoleId(userId, roleId)
                .orElseGet(CompanionModelBinding::new);
        binding.setUserId(userId);
        binding.setRoleId(roleId);
        binding.setProvider(provider);
        binding.setModelName(modelName);
        binding.setEndpoint(endpoint);
        binding.setEnabled(true);
        return modelBindingRepository.save(binding);
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

    private CompanionConversation getOrCreateConversation(Long userId, Long roleId) {
        return conversationRepository.findByUserIdAndRoleId(userId, roleId)
                .orElseGet(() -> {
                    CompanionConversation conv = new CompanionConversation();
                    conv.setUserId(userId);
                    conv.setRoleId(roleId);
                    return conversationRepository.save(conv);
                });
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

    private CompanionRoleDTO toRoleDTO(CompanionRole role) {
        return new CompanionRoleDTO(
                role.getId(),
                role.getName(),
                JsonUtil.toStringList(role.getTraits()),
                role.getTone(),
                role.getBaselineMood(),
                role.getAvatarUrl(),
                role.getModelUrl(),
                role.getModelType(),
                role.getActive()
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
