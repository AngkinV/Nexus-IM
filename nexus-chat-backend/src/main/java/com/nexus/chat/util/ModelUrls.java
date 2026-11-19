package com.nexus.chat.util;

/**
 * Single source of truth for interpreting user-pasted model endpoint URLs.
 *
 * The credential store is shared by the AI assistant (Java probe + Python SDK)
 * and the tavern (raw OpenAI-compatible HTTP). Before this class each consumer
 * guessed its own suffix rules, so the same stored baseUrl worked in one chain
 * and 404'd in another. Every consumer now normalizes through here:
 *
 *   openAiBase     → the API root the OpenAI SDK expects (ends in a version
 *                    segment, /v1 by default; /api/paas/v4 etc. kept as-is)
 *   anthropicBase  → SDK root; the Anthropic SDK appends /v1/messages itself
 *   geminiBase     → API root; REST paths append /v1beta/models/...
 */
public final class ModelUrls {

    public static final String ANTHROPIC_DEFAULT_BASE = "https://api.anthropic.com";
    public static final String GEMINI_DEFAULT_BASE = "https://generativelanguage.googleapis.com";

    private ModelUrls() {}

    /**
     * Normalize any OpenAI-compatible URL to its API root. Accepts bare hosts,
     * versioned roots, and full endpoint URLs pasted from docs:
     *   http://localhost:11434            → http://localhost:11434/v1
     *   https://api.moonshot.cn/v1/       → https://api.moonshot.cn/v1
     *   https://open.bigmodel.cn/api/paas/v4 → unchanged (already versioned)
     *   https://api.x.com/v1/chat/completions → https://api.x.com/v1
     */
    public static String openAiBase(String url) {
        String base = trimSlashes(url);
        if (base == null) return null;
        for (String suffix : new String[]{"/chat/completions", "/embeddings", "/models"}) {
            if (base.endsWith(suffix)) {
                base = trimSlashes(base.substring(0, base.length() - suffix.length()));
                break;
            }
        }
        if (base == null) return null;
        if (isVersionedRoot(base)) return base;
        return base + "/v1";
    }

    public static String chatCompletions(String url) {
        String base = openAiBase(url);
        return base == null ? null : base + "/chat/completions";
    }

    /** Anthropic SDK root (the SDK appends /v1/messages itself). */
    public static String anthropicBase(String url) {
        String base = trimSlashes(url);
        if (base == null) return null;
        if (base.endsWith("/v1/messages")) {
            base = base.substring(0, base.length() - "/v1/messages".length());
        } else if (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - "/v1".length());
        }
        return trimSlashes(base);
    }

    /** Gemini API root (REST paths append /v1beta/models/...). */
    public static String geminiBase(String url) {
        String base = trimSlashes(url);
        if (base == null) return null;
        if (base.endsWith("/v1beta")) {
            base = base.substring(0, base.length() - "/v1beta".length());
        } else if (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - "/v1".length());
        }
        return trimSlashes(base);
    }

    private static boolean isVersionedRoot(String base) {
        int slash = base.lastIndexOf('/');
        String last = slash >= 0 ? base.substring(slash + 1) : base;
        return last.matches("v\\d+");
    }

    private static String trimSlashes(String url) {
        if (url == null) return null;
        String base = url.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base.isEmpty() ? null : base;
    }
}