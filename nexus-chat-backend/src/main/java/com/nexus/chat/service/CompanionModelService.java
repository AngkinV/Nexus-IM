package com.nexus.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.model.CompanionMessage;
import com.nexus.chat.model.CompanionModelBinding;
import com.nexus.chat.model.CompanionRole;
import com.nexus.chat.model.ModelCredential;
import com.nexus.chat.util.ModelUrls;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The tavern's only door to a model provider.
 *
 * Every call — a reply, an inner monologue, a closing line, an invented guest,
 * a connectivity probe — goes through {@link #complete} or {@link #streamReply},
 * so there is one place that builds the request, one place that reads the
 * answer, and one place that says in the log why a call failed. A failure used
 * to turn silently into a canned line; now it is logged with the provider's own
 * words (never the key) before anything falls back.
 */
@Slf4j
@Service
public class CompanionModelService {

    /** A conventional client UA. Nothing browser-specific is required by the API. */
    private static final String USER_AGENT = "Nexus-Tavern/1.0 (OpenAI-Compatible)";
    private static final String DEFAULT_MODEL = "gpt-3.5-turbo";
    /** Matches the settings panel's default, so an unsaved slider and the server agree. */
    static final double DEFAULT_TEMPERATURE = 0.85;

    private final CompanionCryptoService cryptoService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;
    /** Short-timeout client for "nice to have" model calls (closing line, guest
     *  generation). A hanging provider must not hold the UI hostage — these
     *  calls have a plain fallback and are never worth a long wait. */
    private final RestTemplate quickRestTemplate;
    /** Upper bound on one reply. 0 leaves the provider's default in place. */
    private final int maxReplyTokens;

    public CompanionModelService(CompanionCryptoService cryptoService,
                                 RestTemplateBuilder builder,
                                 @Value("${companion.model.read-timeout-sec:60}") int readTimeoutSec,
                                 @Value("${companion.model.max-reply-tokens:1200}") int maxReplyTokens) {
        this.cryptoService = cryptoService;
        this.maxReplyTokens = Math.max(0, maxReplyTokens);
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(8))
                // For a stream this is the longest silence tolerated between two
                // chunks, which in practice means time to first token. Providers
                // that queue requests routinely take longer than the old 20s.
                .setReadTimeout(Duration.ofSeconds(Math.max(10, readTimeoutSec)))
                // Some OpenAI-compatible gateways sit behind Cloudflare, which
                // rejects non-browser-looking clients outright: a bare Java
                // RestTemplate gets "error code: 1010" and never reaches the API.
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
        this.quickRestTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(4))
                .setReadTimeout(Duration.ofSeconds(8))
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
    }

    // ── Conversation ────────────────────────────────────────────────────────

    /** One blocking reply. The content still carries the state marker, if any. */
    public ModelReply generateReply(CompanionPromptBuilder.Context ctx,
                                    List<CompanionMessage> history,
                                    CompanionModelBinding binding,
                                    ModelCredential credential) {
        return complete(restTemplate, "reply", binding, credential,
                CompanionPromptBuilder.messages(ctx, history), temperatureOf(binding), replyCap());
    }

    /**
     * Streaming reply: every content delta from the provider is handed to
     * {@code onDelta} as it arrives, state marker included — the caller filters.
     *
     * Returns everything received, or null when nothing was. A failure mid-stream
     * keeps what already arrived. If {@code onDelta} throws, the provider
     * connection is dropped at once: that is how a reader who left stops the
     * model from writing to nobody.
     */
    public String streamReply(CompanionPromptBuilder.Context ctx,
                              List<CompanionMessage> history,
                              CompanionModelBinding binding,
                              ModelCredential credential,
                              Consumer<String> onDelta) {
        StringBuilder full = new StringBuilder();
        Channel channel = channel(binding, credential);
        if (!channel.ok()) {
            log.warn("Tavern model call skipped [stream]: {}", channel.error());
            return null;
        }
        long started = System.currentTimeMillis();
        try {
            Map<String, Object> payload = payload(binding,
                    CompanionPromptBuilder.messages(ctx, history), temperatureOf(binding), replyCap());
            payload.put("stream", true);

            HttpHeaders headers = jsonHeaders(channel.apiKey());
            headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));

            restTemplate.execute(channel.url(), HttpMethod.POST, request -> {
                request.getHeaders().putAll(headers);
                objectMapper.writeValue(request.getBody(), payload);
            }, response -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) continue;
                        String data = line.substring(5).trim();
                        // DONE ends the stream even if the provider keeps the socket open.
                        if ("[DONE]".equals(data)) break;
                        if (data.isEmpty()) continue;
                        String piece = deltaOf(data);
                        if (piece == null || piece.isEmpty()) continue;
                        full.append(piece);
                        // Outside any catch on purpose: a throw here must end the read.
                        onDelta.accept(piece);
                    }
                }
                return null;
            });
            if (full.length() == 0) {
                log.warn("Tavern model returned an empty stream: model={} ms={}",
                        binding.getModelName(), System.currentTimeMillis() - started);
                return null;
            }
            return full.toString();
        } catch (ReaderGone gone) {
            log.info("Tavern stream stopped by the reader after {} chars", full.length());
            return full.length() == 0 ? null : full.toString();
        } catch (HttpStatusCodeException e) {
            log.warn("Tavern model call failed [stream]: http {} model={} body={}",
                    e.getStatusCode().value(), binding.getModelName(), snippet(e.getResponseBodyAsString()));
            return full.length() == 0 ? null : full.toString();
        } catch (Exception e) {
            // Mid-stream failure: keep what the model already wrote instead of
            // discarding it and triggering the canned fallback.
            log.warn("Tavern model call failed [stream]: {} model={} ms={} kept={} chars",
                    describe(e), binding.getModelName(), System.currentTimeMillis() - started, full.length());
            return full.length() == 0 ? null : full.toString();
        }
    }

    /** Thrown by a stream consumer to say: the reader is gone, stop the model. */
    public static final class ReaderGone extends RuntimeException {
        public ReaderGone() {
            super("reader gone", null, false, false);
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
        StringBuilder context = new StringBuilder();
        String transcript = CompanionPromptBuilder.transcript(history);
        if (!transcript.isBlank()) context.append("最近的对话：\n").append(transcript);
        if (statusSummary != null && !statusSummary.isBlank()) {
            context.append("你此刻的状态：").append(statusSummary).append('\n');
        }
        if (intimacy != null) {
            context.append("你们的亲密程度：").append(intimacy).append("%\n");
        }

        String systemPrompt = "你是" + nameOf(role) + "。"
                + "现在写下你此刻的内心独白：第一人称、现在时、写在括号里的心理活动。"
                + "不要复述对话，不要写成旁白，不要替对方说话。"
                + "两到三句，不超过一百二十字。只输出独白本身，不要加任何说明。";

        return complete(restTemplate, "inner-voice", binding, credential, List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", context.toString().isBlank() ? "写下你此刻的内心独白。" : context.toString())
        ), 0.9, 400);
    }

    // ── Across evenings ─────────────────────────────────────────────────────

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
        String systemPrompt = "你是" + nameOf(role) + "。"
                + CompanionPromptBuilder.scene(sceneKey)
                + "现在这场相遇要结束了。请分两部分回答，用 === 分隔：\n"
                + "第一部分：你对对方说的最后一句话，一到两句，第一人称，动作写在全角括号（）里，不要总结这段对话。\n"
                + "第二部分：用第三人称写一句不超过四十字的记录，只写这段相处里真正发生的事，供你以后回忆。\n"
                + "不要解释格式，不要加任何标注。";

        String transcript = CompanionPromptBuilder.transcript(history);
        ModelReply reply = complete(quickRestTemplate, "closing", binding, credential, List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", transcript.isBlank() ? "（这一晚没有太多交谈。）" : transcript)
        ), 0.8, 300);
        if (!reply.success()) return null;

        String[] parts = reply.content().split("===", 2);
        String line = parts[0].trim();
        String summary = parts.length > 1 ? parts[1].trim() : "";
        if (line.isEmpty()) return null;
        return new ClosingReply(line, summary.isEmpty() ? null : summary);
    }

    /** The last words of an evening, plus the sentence kept from it. */
    public record ClosingReply(String closingLine, String summary) {}

    /**
     * Invent a character for the empty seat. Six labelled lines, so a model that
     * drops or reorders one cannot shift a backstory into the name field.
     */
    public GeneratedRole generateRole(CompanionModelBinding binding,
                                      ModelCredential credential,
                                      String sceneKey) {
        String prompt = "你在为一间酒馆设计一位新客人。" + CompanionPromptBuilder.scene(sceneKey)
                + "请严格按下面六行输出，每行以标签开头，不要多余的话：\n"
                + "名字：两到五个字，像人的名字，不要称号\n"
                + "性格：三个性格词，用顿号分隔\n"
                + "语气：十个字以内，描述语速和用词习惯\n"
                + "此刻：第三人称，这个人此刻在做什么，二十字以内\n"
                + "来历：第三人称，一到两句，这个人从哪来、为什么在这里\n"
                + "开场：这个人见到陌生人时会先说的一句话，动作写在全角括号（）里";

        ModelReply reply = complete(quickRestTemplate, "invite", binding, credential,
                List.of(Map.of("role", "user", "content", prompt)), 0.95, 400);
        if (!reply.success()) return null;
        GeneratedRole role = parseGeneratedRole(reply.content());
        if (role == null) log.warn("Tavern guest generation returned an unusable card: {}", snippet(reply.content()));
        return role;
    }

    /** Read the labelled card. Name, traits and tone are required; the rest may be absent. */
    static GeneratedRole parseGeneratedRole(String content) {
        if (content == null) return null;
        Map<String, String> fields = new LinkedHashMap<>();
        for (String raw : content.split("\\R")) {
            String line = raw.trim().replaceFirst("^[-*\\d.、)）\\s]+", "");
            int sep = indexOfSeparator(line);
            if (sep <= 0) continue;
            String label = line.substring(0, sep).trim();
            String value = line.substring(sep + 1).trim();
            if (value.isEmpty()) continue;
            for (String known : List.of("名字", "性格", "语气", "此刻", "来历", "开场")) {
                if (label.contains(known)) {
                    fields.putIfAbsent(known, value);
                    break;
                }
            }
        }
        String name = fields.get("名字");
        String traits = fields.get("性格");
        String tone = fields.get("语气");
        if (name == null || traits == null || tone == null) return null;
        if (name.length() > 50) name = name.substring(0, 50);
        return new GeneratedRole(name, traits, tone, fields.get("此刻"), fields.get("来历"), fields.get("开场"));
    }

    private static int indexOfSeparator(String line) {
        int a = line.indexOf('：');
        int b = line.indexOf(':');
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    /** A character the model invented for an empty seat. */
    public record GeneratedRole(String name, String traits, String tone,
                                String presence, String background, String greeting) {}

    // ── Diagnostics ─────────────────────────────────────────────────────────

    /**
     * One minimal round trip, used by the settings panel's connectivity test.
     * Reports the provider's own error text so a wrong URL reads differently
     * from a rejected key.
     */
    public ModelReply probe(CompanionModelBinding binding, ModelCredential credential) {
        if (binding == null || credential == null) return ModelReply.failure("no_binding");
        String url = chatCompletionsUrl(binding.getEndpoint());
        if (url == null) return ModelReply.failure("missing_endpoint");
        try {
            // A probe may run against a keyless local endpoint, so the key is optional here.
            String apiKey = credential.getApiKeyEncrypted() == null
                    ? null : cryptoService.decrypt(credential.getApiKeyEncrypted());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", modelOf(binding));
            payload.put("max_tokens", 8);
            payload.put("messages", List.of(Map.of("role", "user", "content", "ping")));

            ResponseEntity<String> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(payload, jsonHeaders(apiKey)), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return ModelReply.failure("bad_response");
            }
            JsonNode root = objectMapper.readTree(response.getBody());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            return ModelReply.success(content.isBlank() ? "ok" : content);
        } catch (HttpStatusCodeException e) {
            // The provider says what is wrong; keep its words.
            String body = snippet(e.getResponseBodyAsString());
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                return ModelReply.failure("invalid_key: " + body);
            }
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return ModelReply.failure("not_found: " + body);
            }
            return ModelReply.failure("http_" + e.getStatusCode().value() + ": " + body);
        } catch (Exception e) {
            return ModelReply.failure("exception: " + snippet(describe(e)));
        }
    }

    /**
     * Turn whatever the user typed as a base URL into a chat-completions URL.
     * Delegates to {@link ModelUrls} so the tavern, the credential probe and the
     * X-Model-Base-URL header handed to Python all share one normalization.
     */
    public String chatCompletionsUrl(String endpoint) {
        return ModelUrls.chatCompletions(endpoint);
    }

    // ── The one request path ────────────────────────────────────────────────

    /**
     * A blocking chat completion. Resolves the channel, sends, reads the first
     * choice, and names the failure when there is one. Never throws.
     */
    private ModelReply complete(RestTemplate client,
                                String purpose,
                                CompanionModelBinding binding,
                                ModelCredential credential,
                                List<Map<String, String>> messages,
                                double temperature,
                                Integer maxTokens) {
        Channel channel = channel(binding, credential);
        if (!channel.ok()) {
            log.warn("Tavern model call skipped [{}]: {}", purpose, channel.error());
            return ModelReply.failure(channel.error());
        }
        long started = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = client.postForEntity(
                    channel.url(),
                    new HttpEntity<>(payload(binding, messages, temperature, maxTokens), jsonHeaders(channel.apiKey())),
                    String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Tavern model call failed [{}]: status {} with no body", purpose, response.getStatusCode().value());
                return ModelReply.failure("bad_response");
            }

            JsonNode choice = objectMapper.readTree(response.getBody()).path("choices").path(0);
            JsonNode contentNode = choice.path("message").path("content");
            String content = contentNode.isMissingNode() || contentNode.isNull() ? null : contentNode.asText();
            if (content == null || content.isBlank()) {
                // "length" here means the cap was spent before any answer was
                // written — typical of reasoning models; raise or zero
                // companion.model.max-reply-tokens.
                log.warn("Tavern model returned no content [{}]: model={} finish_reason={} ms={}",
                        purpose, binding.getModelName(), choice.path("finish_reason").asText("?"),
                        System.currentTimeMillis() - started);
                return ModelReply.failure("empty_content");
            }
            return ModelReply.success(content.trim());
        } catch (HttpStatusCodeException e) {
            log.warn("Tavern model call failed [{}]: http {} model={} body={}",
                    purpose, e.getStatusCode().value(), binding.getModelName(), snippet(e.getResponseBodyAsString()));
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                return ModelReply.failure("invalid_key");
            }
            return ModelReply.failure("http_error");
        } catch (ResourceAccessException e) {
            log.warn("Tavern model call failed [{}]: {} model={} ms={}",
                    purpose, describe(e), binding.getModelName(), System.currentTimeMillis() - started);
            return ModelReply.failure("timeout");
        } catch (Exception e) {
            log.warn("Tavern model call failed [{}]: {} model={}", purpose, describe(e), binding.getModelName());
            return ModelReply.failure("exception");
        }
    }

    /** Where to send the request and what to sign it with — or why we cannot. */
    private record Channel(String url, String apiKey, String error) {
        boolean ok() {
            return error == null;
        }
    }

    private Channel channel(CompanionModelBinding binding, ModelCredential credential) {
        if (binding == null || credential == null || credential.getApiKeyEncrypted() == null) {
            return new Channel(null, null, "missing_binding");
        }
        String apiKey;
        try {
            apiKey = cryptoService.decrypt(credential.getApiKeyEncrypted());
        } catch (Exception e) {
            return new Channel(null, null, "missing_key");
        }
        if (apiKey == null || apiKey.isBlank()) return new Channel(null, null, "missing_key");
        String url = chatCompletionsUrl(binding.getEndpoint());
        if (url == null) return new Channel(null, null, "missing_endpoint");
        return new Channel(url, apiKey, null);
    }

    private Map<String, Object> payload(CompanionModelBinding binding,
                                        List<Map<String, String>> messages,
                                        double temperature,
                                        Integer maxTokens) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", modelOf(binding));
        payload.put("temperature", temperature);
        if (maxTokens != null && maxTokens > 0) payload.put("max_tokens", maxTokens);
        payload.put("messages", messages);
        return payload;
    }

    private HttpHeaders jsonHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) headers.setBearerAuth(apiKey);
        return headers;
    }

    /** The text of one streamed chunk, or null for keep-alives and non-content deltas. */
    private String deltaOf(String data) {
        try {
            JsonNode delta = objectMapper.readTree(data).path("choices").path(0).path("delta").path("content");
            return delta.isMissingNode() || delta.isNull() ? null : delta.asText();
        } catch (Exception ignored) {
            // A provider may interleave non-JSON keep-alives; skip them.
            return null;
        }
    }

    private Integer replyCap() {
        return maxReplyTokens > 0 ? maxReplyTokens : null;
    }

    private static String modelOf(CompanionModelBinding binding) {
        return binding.getModelName() != null && !binding.getModelName().isBlank()
                ? binding.getModelName() : DEFAULT_MODEL;
    }

    /** Per-role sampling temperature, clamped to the range the UI offers. */
    private static double temperatureOf(CompanionModelBinding binding) {
        if (binding == null || binding.getTemperature() == null) return DEFAULT_TEMPERATURE;
        return Math.max(0.1, Math.min(1.5, binding.getTemperature()));
    }

    private static String nameOf(CompanionRole role) {
        return role != null && role.getName() != null && !role.getName().isBlank() ? role.getName() : "一个角色";
    }

    private static String describe(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** Provider text for the log: one line, bounded. Never contains our key. */
    private static String snippet(String text) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 300) : flat;
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
