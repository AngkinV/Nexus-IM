package com.nexus.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexus.chat.model.CompanionMessage;
import com.nexus.chat.model.CompanionModelBinding;
import com.nexus.chat.model.CompanionRole;
import com.nexus.chat.model.ModelChannels;
import com.nexus.chat.model.ModelCredential;
import com.nexus.chat.service.agent.AgentProviderService.ResolvedProvider;
import com.nexus.chat.service.agent.PythonModelGateway;
import com.nexus.chat.util.CompanionStateTag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static com.nexus.chat.service.CompanionModelResolver.trimmed;

/** Tavern prompts and reply formats. The Python gateway owns all model transport. */
@Service
public class CompanionModelService {
    static final double DEFAULT_TEMPERATURE = 0.85;
    private static final int QUICK_TIMEOUT_SEC = 8;
    private final CompanionCryptoService cryptoService;
    private final PythonModelGateway gateway;
    private final int timeoutSec;
    private final int maxReplyTokens;

    public CompanionModelService(CompanionCryptoService cryptoService, PythonModelGateway gateway,
                                 @Value("${companion.model.read-timeout-sec:60}") int timeoutSec,
                                 @Value("${companion.model.max-reply-tokens:1200}") int maxReplyTokens) {
        this.cryptoService = cryptoService;
        this.gateway = gateway;
        this.timeoutSec = Math.max(10, Math.min(300, timeoutSec));
        this.maxReplyTokens = maxReplyTokens > 0 ? Math.min(8192, maxReplyTokens) : 1200;
    }

    /** The caller filters the state marker on both the blocking and streaming paths. */
    public ModelReply generateReply(CompanionPromptBuilder.Context ctx, List<CompanionMessage> history,
                                    CompanionModelBinding binding, ModelCredential credential) {
        return call(binding, credential, CompanionPromptBuilder.messages(ctx, history),
                temperatureOf(binding), maxReplyTokens, timeoutSec, null);
    }

    public ModelReply streamReply(CompanionPromptBuilder.Context ctx, List<CompanionMessage> history,
                                  CompanionModelBinding binding, ModelCredential credential, Consumer<String> onDelta) {
        return call(binding, credential, CompanionPromptBuilder.messages(ctx, history),
                temperatureOf(binding), maxReplyTokens, timeoutSec, Objects.requireNonNull(onDelta));
    }

    /** Thrown by a stream consumer to unwind the gateway read when the reader disconnects. */
    public static final class ReaderGone extends RuntimeException {
        public ReaderGone() { super("reader gone", null, false, false); }
    }

    /** Test an actual role reply, with its real sampling, token cap and timeout, without filing a turn. */
    public ModelReply probe(CompanionPromptBuilder.Context ctx, List<CompanionMessage> history,
                            CompanionModelBinding binding, ModelCredential credential) {
        ModelReply reply = generateReply(ctx, history, binding, credential);
        if (!reply.success()) return reply;
        String visible = CompanionStateTag.parse(reply.content()).text();
        return visible.isBlank() ? ModelReply.emptyContent() : ModelReply.success(visible);
    }

    public ModelReply generateInnerVoice(CompanionRole role,
                                         List<CompanionMessage> history,
                                         CompanionModelBinding binding,
                                         ModelCredential credential,
                                         String statusSummary,
                                         Integer intimacy) {
        return generateInnerVoiceWithContext(CompanionPromptBuilder.Context.bare(role, null), history, binding, credential, statusSummary, intimacy);
    }

    public ModelReply generateInnerVoiceWithContext(CompanionPromptBuilder.Context ctx, List<CompanionMessage> history,
                                        CompanionModelBinding binding, ModelCredential credential,
                                        String statusSummary, Integer intimacy) {
        StringBuilder context = new StringBuilder();
        String transcript = CompanionPromptBuilder.transcript(history);
        if (!transcript.isBlank()) context.append("最近的对话：\n").append(transcript);
        if (statusSummary != null && !statusSummary.isBlank()) {
            context.append("你此刻的状态：").append(statusSummary).append('\n');
        }
        if (intimacy != null) {
            context.append("你们的亲密程度：").append(intimacy).append("%\n");
        }

        String systemPrompt = CompanionPromptBuilder.system(ctx.withoutState()) + "\n"
                + "现在写下你此刻的内心独白：第一人称、现在时、写在括号里的心理活动。"
                + "不要复述对话，不要写成旁白，不要替对方说话。"
                + "两到三句，不超过一百二十字。只输出独白本身，不要加任何说明。";

        return call(binding, credential, List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", context.toString().isBlank() ? "写下你此刻的内心独白。" : context.toString())
        ), 0.9, 400, timeoutSec, null);
    }

    /** The last words of an evening, plus the sentence kept from it. */
    public record ClosingReply(String closingLine, String summary) {}

    public ClosingReply generateClosing(CompanionRole role,
                                        List<CompanionMessage> history,
                                        CompanionModelBinding binding,
                                        ModelCredential credential,
                                        String sceneKey) {
        return generateClosing(CompanionPromptBuilder.Context.bare(role, sceneKey), history, binding, credential);
    }

    public ClosingReply generateClosing(CompanionPromptBuilder.Context ctx, List<CompanionMessage> history,
                                        CompanionModelBinding binding, ModelCredential credential) {
        String systemPrompt = CompanionPromptBuilder.system(ctx.withoutState()) + "\n"
                + "现在这场相遇要结束了。请分两部分回答，用 === 分隔：\n"
                + "第一部分：你对对方说的最后一句话，一到两句，第一人称，动作写在全角括号（）里，不要总结这段对话。\n"
                + "第二部分：用第三人称写一句不超过四十字的记录，只写这段相处里真正发生的事，供你以后回忆。\n"
                + "不要解释格式，不要加任何标注。";

        String transcript = CompanionPromptBuilder.transcript(history);
        ModelReply reply = call(binding, credential, List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", transcript.isBlank() ? "（这一晚没有太多交谈。）" : transcript)
        ), 0.8, 300, QUICK_TIMEOUT_SEC, null);
        if (!reply.success()) return null;

        String[] parts = reply.content().split("===", 2);
        String line = parts[0].trim();
        String summary = parts.length > 1 ? parts[1].trim() : "";
        if (line.isEmpty()) return null;
        return new ClosingReply(line, summary.isEmpty() ? null : summary);
    }

    public GeneratedRole generateRole(CompanionModelBinding binding, ModelCredential credential, String sceneKey) {
        String prompt = "你在为一间酒馆设计一位新客人。" + CompanionPromptBuilder.scene(sceneKey)
                + "请严格按下面六行输出，每行以标签开头，不要多余的话：\n"
                + "名字：两到五个字，像人的名字，不要称号\n"
                + "性格：三个性格词，用顿号分隔\n"
                + "语气：十个字以内，描述语速和用词习惯\n"
                + "此刻：第三人称，这个人此刻在做什么，二十字以内\n"
                + "来历：第三人称，一到两句，这个人从哪来、为什么在这里\n"
                + "开场：这个人见到陌生人时会先说的一句话，动作写在全角括号（）里";

        ModelReply reply = call(binding, credential, List.of(Map.of("role", "user", "content", prompt)),
                0.95, 400, QUICK_TIMEOUT_SEC, null);
        return reply.success() ? parseGeneratedRole(reply.content()) : null;
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

    public record GeneratedRole(String name, String traits, String tone,
                                String presence, String background, String greeting) {}

    private ModelReply call(CompanionModelBinding binding, ModelCredential credential,
                            List<Map<String, String>> messages, double temperature, int maxTokens,
                            int timeout, Consumer<String> onDelta) {
        if (binding == null || !Boolean.TRUE.equals(binding.getEnabled()) || credential == null
                || credential.getUserId() == null || !credential.getUserId().equals(binding.getUserId())
                || !ModelCredential.PURPOSE_CHAT.equals(credential.getPurpose())
                || trimmed(credential.getProvider()) == null || !credential.getProvider().equals(binding.getProvider())) {
            return ModelReply.configuration("no_credential");
        }
        String base = trimmed(credential.getBaseUrl());
        if (base == null) return ModelReply.configuration("no_endpoint");
        if (!CompanionModelResolver.matchesEndpoint(binding.getEndpoint(), base)) {
            return ModelReply.configuration("endpoint_mismatch");
        }
        String model = trimmed(binding.getModelName());
        if (model == null) model = trimmed(credential.getDefaultModel());
        if (model == null) return ModelReply.configuration("no_model");

        String key;
        try {
            key = credential.getApiKeyEncrypted() == null ? null : cryptoService.decrypt(credential.getApiKeyEncrypted());
        } catch (RuntimeException e) {
            return ModelReply.configuration("unreadable_api_key");
        }
        boolean hasKey = key != null && !key.isBlank();
        if (!hasKey && !ModelChannels.keyOptional(credential)) return ModelReply.configuration("no_api_key");
        ResolvedProvider channel = new ResolvedProvider(credential.getId(), ModelChannels.adapterName(credential, hasKey),
                credential.getDisplayName(), base, model, key);
        // Do not catch exceptions from onDelta: unwinding is how a reader releases the stream.
        long owner = credential.getUserId();
        JsonNode result = onDelta == null ? gateway.complete(owner, channel, messages, temperature, maxTokens, timeout)
                : gateway.stream(owner, channel, messages, temperature, maxTokens, timeout, onDelta);
        return ModelReply.from(result);
    }

    static double temperatureOf(CompanionModelBinding binding) {
        if (binding == null || binding.getTemperature() == null || !Double.isFinite(binding.getTemperature())) {
            return DEFAULT_TEMPERATURE;
        }
        return Math.max(0.1, Math.min(1.5, binding.getTemperature()));
    }

    private static String nameOf(CompanionRole role) {
        return role != null && role.getName() != null && !role.getName().isBlank() ? role.getName() : "一个角色";
    }

    public record ModelReply(String content, boolean partial, JsonNode error) {
        public boolean success() { return error == null && !partial && content != null && !content.isBlank(); }
        public static ModelReply success(String content) { return new ModelReply(content, false, null); }

        static ModelReply from(JsonNode result) {
            if (result == null || !result.isObject()) return failure("INCOMPLETE", "Missing model response");
            String text = result.path("text").textValue();
            boolean hasText = text != null && !text.isBlank();
            JsonNode error = result.get("error");
            if (error != null && !error.isNull()) return new ModelReply(text, hasText, error);
            String finish = result.path("finishReason").asText("");
            if (result.path("partial").asBoolean() || !"stop".equals(finish)) {
                return new ModelReply(text, hasText, localError("INCOMPLETE", "Model reply was interrupted"));
            }
            return hasText ? success(text) : emptyContent();
        }

        static ModelReply configuration(String reason) { return failure("MODEL_CONFIG", reason); }
        static ModelReply emptyContent() { return failure("EMPTY_CONTENT", "Model returned no visible reply"); }
        private static ModelReply failure(String code, String summary) {
            return new ModelReply(null, false, localError(code, summary));
        }
        private static JsonNode localError(String code, String summary) {
            return JsonNodeFactory.instance.objectNode().put("code", code).put("summary", summary);
        }
    }
}
