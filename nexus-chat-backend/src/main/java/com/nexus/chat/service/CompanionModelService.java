package com.nexus.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.model.CompanionMessage;
import com.nexus.chat.model.CompanionModelBinding;
import com.nexus.chat.model.CompanionRole;
import com.nexus.chat.model.ModelCredential;
import com.nexus.chat.util.ModelUrls;
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
import java.util.function.Consumer;

@Service
public class CompanionModelService {

    private final CompanionCryptoService cryptoService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;
    /** Short-timeout client for "nice to have" model calls (closing line, guest
     *  generation). A hanging provider must not hold the UI hostage — these
     *  calls have a plain fallback and are never worth waiting 20s for. */
    private final RestTemplate quickRestTemplate;

    public CompanionModelService(CompanionCryptoService cryptoService, RestTemplateBuilder builder) {
        this.cryptoService = cryptoService;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(8))
                .setReadTimeout(Duration.ofSeconds(20))
                // Some OpenAI-compatible gateways sit behind Cloudflare, which
                // rejects non-browser-looking clients outright: a bare Java
                // RestTemplate gets "error code: 1010" and never reaches the API.
                // The Python side is unaffected because the openai SDK sends its
                // own UA. This header is the whole difference between the tavern
                // working and not.
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
        this.quickRestTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(4))
                .setReadTimeout(Duration.ofSeconds(8))
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
    }

    /** A conventional client UA. Nothing browser-specific is required by the API. */
    private static final String USER_AGENT = "Nexus-Tavern/1.0 (OpenAI-Compatible)";

    public ModelReply generateReply(CompanionRole role,
                                    List<CompanionMessage> history,
                                    CompanionModelBinding binding,
                                    ModelCredential credential) {
        return generateReply(role, history, binding, credential, null);
    }

    public ModelReply generateReply(CompanionRole role,
                                    List<CompanionMessage> history,
                                    CompanionModelBinding binding,
                                    ModelCredential credential,
                                    String sceneKey) {
        try {
            if (binding == null || credential == null || credential.getApiKeyEncrypted() == null) {
                return ModelReply.failure("missing_binding");
            }

            String apiKey = cryptoService.decrypt(credential.getApiKeyEncrypted());
            if (apiKey == null || apiKey.isBlank()) {
                return ModelReply.failure("missing_key");
            }

            String url = chatCompletionsUrl(binding.getEndpoint());
            if (url == null) {
                return ModelReply.failure("missing_endpoint");
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("model", binding.getModelName() != null ? binding.getModelName() : "gpt-3.5-turbo");
            payload.put("temperature", temperatureOf(binding));
            payload.put("messages", buildMessages(role, history, sceneKey, binding));

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

    /**
     * Her inner monologue: first person, present tense, what she does not say out
     * loud. Written from the bond figures and the last few turns, so it is a
     * reading of this conversation rather than a generic mood line.
     */
    public ModelReply generateInnerVoice(CompanionRole role,
                                         List<CompanionMessage> history,
                                         CompanionModelBinding binding,
                                         ModelCredential credential,
                                         String statusSummary,
                                         Integer intimacy) {
        try {
            if (binding == null || credential == null || credential.getApiKeyEncrypted() == null) {
                return ModelReply.failure("missing_binding");
            }
            String apiKey = cryptoService.decrypt(credential.getApiKeyEncrypted());
            if (apiKey == null || apiKey.isBlank()) {
                return ModelReply.failure("missing_key");
            }
            String url = chatCompletionsUrl(binding.getEndpoint());
            if (url == null) {
                return ModelReply.failure("missing_endpoint");
            }

            StringBuilder context = new StringBuilder();
            if (history != null && !history.isEmpty()) {
                context.append("最近的对话：\n");
                for (CompanionMessage msg : history) {
                    String who = msg.getSenderType() == CompanionMessage.SenderType.user ? "对方" : "你";
                    context.append(who).append("：").append(msg.getContent()).append('\n');
                }
            }
            if (statusSummary != null && !statusSummary.isBlank()) {
                context.append("你此刻的状态：").append(statusSummary).append('\n');
            }
            if (intimacy != null) {
                context.append("你们的亲密程度：").append(intimacy).append("%\n");
            }

            String systemPrompt = "你是" + (role.getName() != null ? role.getName() : "一个角色") + "。"
                    + "现在写下你此刻的内心独白：第一人称、现在时、写在括号里的心理活动。"
                    + "不要复述对话，不要写成旁白，不要替对方说话。"
                    + "两到三句，不超过一百二十字。只输出独白本身，不要加任何说明。";

            Map<String, Object> payload = new HashMap<>();
            payload.put("model", binding.getModelName() != null ? binding.getModelName() : "gpt-3.5-turbo");
            payload.put("temperature", 0.9);
            payload.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", context.toString().isBlank() ? "写下你此刻的内心独白。" : context.toString())
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(payload, headers), String.class);

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

    /**
     * Streaming reply: every SSE `data:` line from the provider is turned into a
     * delta callback. Returns the full text so the caller can persist it, or null
     * when nothing was streamed (the caller then falls back). On a mid-stream
     * failure the text received so far is returned, never discarded.
     */
    public String streamReply(CompanionRole role,
                              List<CompanionMessage> history,
                              CompanionModelBinding binding,
                              ModelCredential credential,
                              Consumer<String> onDelta) {
        return streamReply(role, history, binding, credential, null, onDelta);
    }

    public String streamReply(CompanionRole role,
                              List<CompanionMessage> history,
                              CompanionModelBinding binding,
                              ModelCredential credential,
                              String sceneKey,
                              Consumer<String> onDelta) {
        StringBuilder full = new StringBuilder();
        try {
            if (binding == null || credential == null || credential.getApiKeyEncrypted() == null) return null;
            String apiKey = cryptoService.decrypt(credential.getApiKeyEncrypted());
            if (apiKey == null || apiKey.isBlank()) return null;
            String url = chatCompletionsUrl(binding.getEndpoint());
            if (url == null) return null;

            Map<String, Object> payload = new HashMap<>();
            payload.put("model", binding.getModelName() != null ? binding.getModelName() : "gpt-3.5-turbo");
            payload.put("temperature", temperatureOf(binding));
            payload.put("stream", true);
            payload.put("messages", buildMessages(role, history, sceneKey, binding));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
            headers.setBearerAuth(apiKey);

            restTemplate.execute(url, HttpMethod.POST, request -> {
                request.getHeaders().putAll(headers);
                objectMapper.writeValue(request.getBody(), payload);
            }, response -> {
                if (!response.getStatusCode().is2xxSuccessful()) return null;
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(response.getBody(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) continue;
                        String data = line.substring(5).trim();
                        if (data.isEmpty() || "[DONE]".equals(data)) continue;
                        try {
                            JsonNode node = objectMapper.readTree(data);
                            JsonNode delta = node.path("choices").path(0).path("delta").path("content");
                            if (delta.isMissingNode() || delta.isNull()) continue;
                            String piece = delta.asText();
                            if (piece == null || piece.isEmpty()) continue;
                            full.append(piece);
                            onDelta.accept(piece);
                        } catch (Exception ignored) {
                            // A provider may interleave non-JSON keep-alives; skip them.
                        }
                    }
                }
                return null;
            });
            return full.length() == 0 ? null : full.toString();
        } catch (Exception e) {
            // Mid-stream failure: keep what the model already streamed instead
            // of discarding it and triggering the canned fallback.
            return full.length() == 0 ? null : full.toString();
        }
    }

    /**
     * One minimal round trip, used by the settings panel's connectivity test.
     * Reports the provider's own error text so a wrong URL reads differently
     * from a rejected key.
     */
    public ModelReply probe(CompanionModelBinding binding, ModelCredential credential) {
        if (binding == null || credential == null) return ModelReply.failure("no_binding");
        try {
            String apiKey = cryptoService.decrypt(credential.getApiKeyEncrypted());
            String url = chatCompletionsUrl(binding.getEndpoint());
            if (url == null) return ModelReply.failure("missing_endpoint");

            Map<String, Object> payload = new HashMap<>();
            payload.put("model", binding.getModelName() != null ? binding.getModelName() : "gpt-3.5-turbo");
            payload.put("max_tokens", 8);
            payload.put("messages", List.of(Map.of("role", "user", "content", "ping")));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (apiKey != null && !apiKey.isBlank()) headers.setBearerAuth(apiKey);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(payload, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return ModelReply.failure("bad_response");
            }
            JsonNode root = objectMapper.readTree(response.getBody());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            return ModelReply.success(content.isBlank() ? "ok" : content);
        } catch (HttpClientErrorException e) {
            // The provider says what is wrong; keep its words.
            String body = e.getResponseBodyAsString();
            if (body != null && body.length() > 300) body = body.substring(0, 300);
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                return ModelReply.failure("invalid_key: " + body);
            }
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return ModelReply.failure("not_found: " + body);
            }
            return ModelReply.failure("http_" + e.getStatusCode().value() + ": " + body);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (msg.length() > 300) msg = msg.substring(0, 300);
            return ModelReply.failure("exception: " + msg);
        }
    }

    /**
     * Turn whatever the user typed as a base URL into a chat-completions URL.
     * Delegates to {@link ModelUrls} so the tavern, the credential probe and the
     * X-Model-Base-URL header handed to Python all share one normalization —
     * versioned non-/v1 roots (zhipu /api/paas/v4) survive instead of getting a
     * second /v1 glued on.
     */
    public String chatCompletionsUrl(String endpoint) {
        return ModelUrls.chatCompletions(endpoint);
    }

    /** Per-role sampling temperature, clamped to the range the UI offers. */
    private double temperatureOf(CompanionModelBinding binding) {
        if (binding == null || binding.getTemperature() == null) return 0.7;
        return Math.max(0.1, Math.min(1.5, binding.getTemperature()));
    }

    private List<Map<String, String>> buildMessages(CompanionRole role,
                                                    List<CompanionMessage> history,
                                                    String sceneKey,
                                                    CompanionModelBinding binding) {
        List<Map<String, String>> messages = new ArrayList<>();
        String systemPrompt = buildSystemPrompt(role, sceneKey, binding);
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

    /**
     * The line she says on the way out, and the sentence worth remembering.
     * One call, two outputs separated by a marker so neither can bleed into the
     * other. Returns null on any failure — closing the evening must still work
     * without a model.
     */
    public ClosingReply generateClosing(CompanionRole role,
                                        List<CompanionMessage> history,
                                        CompanionModelBinding binding,
                                        ModelCredential credential,
                                        String sceneKey) {
        try {
            if (binding == null || credential == null || credential.getApiKeyEncrypted() == null) return null;
            String apiKey = cryptoService.decrypt(credential.getApiKeyEncrypted());
            if (apiKey == null || apiKey.isBlank()) return null;
            String url = chatCompletionsUrl(binding.getEndpoint());
            if (url == null) return null;

            String scene = sceneKey == null ? "" : SCENE_PROMPTS.getOrDefault(sceneKey, "");

            StringBuilder context = new StringBuilder();
            if (history != null) {
                for (CompanionMessage msg : history) {
                    String who = msg.getSenderType() == CompanionMessage.SenderType.user ? "对方" : "你";
                    context.append(who).append("：").append(msg.getContent()).append('\n');
                }
            }

            String systemPrompt = "你是" + (role != null && role.getName() != null ? role.getName() : "一个角色") + "。"
                    + scene
                    + "现在这场相遇要结束了。请分两部分回答，用 === 分隔：\n"
                    + "第一部分：你对对方说的最后一句话，一到两句，第一人称，带一点动作，不要总结这段对话。\n"
                    + "第二部分：用第三人称写一句不超过四十字的记录，只写这段相处里真正发生的事，供你以后回忆。\n"
                    + "不要解释格式，不要加任何标注。";

            Map<String, Object> payload = new HashMap<>();
            payload.put("model", binding.getModelName() != null ? binding.getModelName() : "gpt-3.5-turbo");
            payload.put("temperature", 0.8);
            payload.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", context.toString().isBlank() ? "（这一晚没有太多交谈。）" : context.toString())
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            ResponseEntity<String> response = quickRestTemplate.postForEntity(url, new HttpEntity<>(payload, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) return null;

            JsonNode root = objectMapper.readTree(response.getBody());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) return null;

            String[] parts = content.split("===", 2);
            String line = parts[0].trim();
            String summary = parts.length > 1 ? parts[1].trim() : "";
            if (line.isEmpty()) return null;
            return new ClosingReply(line, summary.isEmpty() ? null : summary);
        } catch (Exception e) {
            return null;
        }
    }

    /** The last words of an evening, plus the sentence kept from it. */
    public record ClosingReply(String closingLine, String summary) {}

    /**
     * Invent a character for the empty seat: a name, a way of speaking, a few
     * traits, and one line of presence. Shaped to land straight into a
     * CompanionRole so nothing downstream needs a special case.
     */
    public GeneratedRole generateRole(CompanionModelBinding binding,
                                      ModelCredential credential,
                                      String sceneKey) {
        try {
            if (binding == null || credential == null || credential.getApiKeyEncrypted() == null) return null;
            String apiKey = cryptoService.decrypt(credential.getApiKeyEncrypted());
            if (apiKey == null || apiKey.isBlank()) return null;
            String url = chatCompletionsUrl(binding.getEndpoint());
            if (url == null) return null;

            String scene = sceneKey == null ? "" : SCENE_PROMPTS.getOrDefault(sceneKey, "");

            String systemPrompt = "你在为一间雨夜酒馆设计一位新客人。" + scene
                    + "请严格按下面四行输出，不要编号，不要多余的话：\n"
                    + "第一行：名字（两到五个字，像人的名字，不要称号）\n"
                    + "第二行：三个性格词，用顿号分隔\n"
                    + "第三行：说话方式（十个字以内，描述语速、用词习惯）\n"
                    + "第四行：一句话描述这个人此刻在做什么（二十字以内，第三人称）";

            Map<String, Object> payload = new HashMap<>();
            payload.put("model", binding.getModelName() != null ? binding.getModelName() : "gpt-3.5-turbo");
            payload.put("temperature", 0.95);
            payload.put("messages", List.of(Map.of("role", "user", "content", systemPrompt)));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            ResponseEntity<String> response = quickRestTemplate.postForEntity(url, new HttpEntity<>(payload, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) return null;

            JsonNode root = objectMapper.readTree(response.getBody());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) return null;

            String[] lines = content.split("\\R");
            java.util.List<String> clean = new ArrayList<>();
            for (String line : lines) {
                String trimmed = line.trim().replaceFirst("^\\d+[.、)）]\\s*", "");
                if (!trimmed.isEmpty()) clean.add(trimmed);
            }
            if (clean.size() < 4) return null;
            return new GeneratedRole(clean.get(0), clean.get(1), clean.get(2), clean.get(3));
        } catch (Exception e) {
            return null;
        }
    }

    /** A character the model invented for an empty seat. */
    public record GeneratedRole(String name, String traits, String tone, String presence) {}

    /**
     * Scene atmosphere, keyed the same way the client keys its scene list.
     * Unknown keys fall through to no scene line at all rather than to a guess —
     * a wrong room is worse than no room.
     */
    private static final Map<String, String> SCENE_PROMPTS = Map.of(
            "rain-fireplace",
            "此刻是雨夜，你们在一间只有壁炉亮着的酒馆里。窗外寒雨不停，室内干燥温暖，柴火偶尔劈啪作响。"
                    + "你话不多，动作慢，注意力放在对方身上而不是酒馆里其他东西上。",
            "late-rain",
            "此刻是深夜，外面下着大雨，你们为了避雨留在打烊前的酒馆里。灯快灭了，店里没有别人。"
                    + "气氛安静而私密，你会更直接一点，也更愿意说些平时不说的事。",
            "empty-bar",
            "此刻酒馆里几乎没有人，只有吧台后的你和你面前的人。空间开阔，声音清楚，任何一句话都显得比平时更重。"
                    + "你会更留意对方的表情和停顿，回答偏短，留白多。",
            "closing-time",
            "此刻是打烊前最后一段时间，店里正在收拾，天亮之前你们就得分开。"
                    + "对话带着一点告别感，你会说得更坦白，也更珍惜这几句话。"
    );

    /**
     * How hard to hold the character to their persona, expressed as an
     * instruction rather than a number — the model cannot act on "92%".
     * Null or missing keeps the middle of the range.
     */
    private String personaLockLine(Integer personaLock) {
        if (personaLock == null) return "";
        if (personaLock >= 95) {
            return "严格遵守上面的性格设定，不因对话内容改变立场、口吻或说话习惯。";
        }
        if (personaLock >= 80) {
            return "保持上面的性格设定，可以随情绪有细微起伏，但不要偏离。";
        }
        if (personaLock >= 65) {
            return "大体保持上面的性格设定，允许在情绪强烈时有明显变化。";
        }
        return "以上性格设定作为参考，允许自由发挥。";
    }

    private String buildSystemPrompt(CompanionRole role, String sceneKey, CompanionModelBinding binding) {
        if (role == null) return "你是一个温柔的中文陪伴助手。";
        String name = role.getName() != null ? role.getName() : "陪伴助手";
        String tone = role.getTone() != null ? role.getTone() : "温和";
        String traits = role.getTraits() != null ? role.getTraits() : "";

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是").append(name).append("，你的话风是").append(tone).append("。");
        if (!traits.isBlank()) {
            prompt.append("你具备这些性格特征：").append(traits).append("。");
        }

        String scene = sceneKey == null ? null : SCENE_PROMPTS.get(sceneKey);
        if (scene != null) {
            prompt.append(scene);
        }

        String lock = personaLockLine(binding == null ? null : binding.getPersonaLock());
        if (!lock.isBlank()) {
            prompt.append(lock);
        }

        prompt.append("请用中文回应，以角色身份说话，不要解释自己是模型，不要跳出设定。");
        return prompt.toString();
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
