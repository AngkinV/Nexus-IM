package com.nexus.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.model.CompanionMessage;
import com.nexus.chat.model.CompanionModelBinding;
import com.nexus.chat.model.CompanionRole;
import com.nexus.chat.model.ModelCredential;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CompanionModelService {

    private final CompanionCryptoService cryptoService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;

    public CompanionModelService(CompanionCryptoService cryptoService, RestTemplateBuilder builder) {
        this.cryptoService = cryptoService;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(20))
                .build();
    }

    public ModelReply generateReply(CompanionRole role,
                                    List<CompanionMessage> history,
                                    CompanionModelBinding binding,
                                    ModelCredential credential) {
        try {
            if (binding == null || credential == null || credential.getApiKeyEncrypted() == null) {
                return ModelReply.failure("missing_binding");
            }

            String apiKey = cryptoService.decrypt(credential.getApiKeyEncrypted());
            if (apiKey == null || apiKey.isBlank()) {
                return ModelReply.failure("missing_key");
            }

            String endpoint = binding.getEndpoint();
            if (endpoint == null || endpoint.isBlank()) {
                return ModelReply.failure("missing_endpoint");
            }
            String url = endpoint.endsWith("/") ? endpoint + "v1/chat/completions" : endpoint + "/v1/chat/completions";

            Map<String, Object> payload = new HashMap<>();
            payload.put("model", binding.getModelName() != null ? binding.getModelName() : "gpt-3.5-turbo");
            payload.put("temperature", 0.7);
            payload.put("messages", buildMessages(role, history));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    new HttpEntity<>(payload, headers),
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return ModelReply.failure("bad_response");
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            String content = contentNode.isMissingNode() ? null : contentNode.asText();
            if (content == null || content.isBlank()) {
                return ModelReply.failure("empty_content");
            }
            return ModelReply.success(content.trim());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                return ModelReply.failure("invalid_key");
            }
            return ModelReply.failure("http_error");
        } catch (Exception e) {
            return ModelReply.failure("exception");
        }
    }

    private List<Map<String, String>> buildMessages(CompanionRole role, List<CompanionMessage> history) {
        List<Map<String, String>> messages = new ArrayList<>();
        String systemPrompt = buildSystemPrompt(role);
        messages.add(Map.of("role", "system", "content", systemPrompt));

        if (history != null) {
            for (CompanionMessage msg : history) {
                String roleType = msg.getSenderType() == CompanionMessage.SenderType.user ? "user" : "assistant";
                messages.add(Map.of(
                        "role", roleType,
                        "content", msg.getContent() == null ? "" : msg.getContent()
                ));
            }
        }
        return messages;
    }

    private String buildSystemPrompt(CompanionRole role) {
        if (role == null) return "你是一个温柔的中文陪伴助手。";
        String name = role.getName() != null ? role.getName() : "陪伴助手";
        String tone = role.getTone() != null ? role.getTone() : "温和";
        String traits = role.getTraits() != null ? role.getTraits() : "";
        return "你是" + name + "，你的话风是" + tone + "。你具备这些性格特征：" + traits + "。" +
                "请用中文、陪伴型、不过度游戏化的方式回应用户。";
    }

    public record ModelReply(boolean success, String content, String error) {
        public static ModelReply success(String content) {
            return new ModelReply(true, content, null);
        }

        public static ModelReply failure(String error) {
            return new ModelReply(false, null, error);
        }
    }
}
