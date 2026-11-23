package com.nexus.chat.model;

import java.util.Locale;
import java.util.Set;

/**
 * How a saved model channel is addressed.
 *
 * A channel has three separate facts: its id ({@link ModelCredential#getProvider()},
 * unique per user and purpose), the vendor it was created from, and the wire protocol
 * its endpoint speaks. The Python model service picks its adapter from one name, so
 * this is the single place that turns those facts into that name.
 */
public final class ModelChannels {
    public static final String OPENAI_COMPATIBLE = "openai-compatible";
    public static final String ANTHROPIC = "anthropic";
    public static final String GEMINI = "gemini";
    public static final String CUSTOM_VENDOR = "custom";

    private static final Set<String> FORMATS = Set.of(OPENAI_COMPATIBLE, ANTHROPIC, GEMINI);
    private static final Set<String> ANTHROPIC_NAMES = Set.of("anthropic", "claude");
    private static final Set<String> GEMINI_NAMES = Set.of("gemini", "google");
    /** OpenAI-compatible vendors the Python service knows a default endpoint for. */
    private static final Set<String> KNOWN_OPENAI_COMPATIBLE = Set.of(
            "openai", "deepseek", "moonshot", "zhipu", "tongyi", "together", "groq", "ollama");
    /** Servers that run on the user's machine and take no API key. */
    private static final Set<String> KEYLESS_VENDORS = Set.of("ollama", "lmstudio");
    /** The one name the Python service accepts without a key. */
    private static final String KEYLESS_ADAPTER = "ollama";

    private ModelChannels() {}

    /** A supported protocol name, or null when the value is blank. */
    public static String normalizeFormat(String raw) {
        String value = lower(raw);
        if (value == null) return null;
        if (!FORMATS.contains(value)) throw new IllegalArgumentException("Unsupported API format");
        return value;
    }

    /** The protocol in effect: the saved one, or what the channel id meant before protocols were saved. */
    public static String apiFormat(ModelCredential channel) {
        String saved = lower(channel.getApiFormat());
        if (saved != null) return saved;
        String id = lower(channel.getProvider());
        if (id != null && ANTHROPIC_NAMES.contains(id)) return ANTHROPIC;
        if (id != null && GEMINI_NAMES.contains(id)) return GEMINI;
        return OPENAI_COMPATIBLE;
    }

    /** The vendor in effect. Older rows used the vendor name as the channel id. */
    public static String vendor(ModelCredential channel) {
        String saved = lower(channel.getVendor());
        return saved != null ? saved : lower(channel.getProvider());
    }

    /** True when this channel may be used without an API key. */
    public static boolean keyOptional(ModelCredential channel) {
        if (!OPENAI_COMPATIBLE.equals(apiFormat(channel))) return false;
        String vendor = vendor(channel);
        return vendor != null && KEYLESS_VENDORS.contains(vendor);
    }

    /**
     * The provider name sent to the Python model service.
     *
     * Rows without a saved protocol keep sending their id, exactly as before. Rows with
     * one send a name the service recognises, so "anthropic-2" still reaches the Anthropic
     * adapter and a relay named after Claude is not mistaken for Anthropic's own API.
     */
    public static String adapterName(ModelCredential channel, boolean hasKey) {
        if (lower(channel.getApiFormat()) == null) return channel.getProvider();
        String format = apiFormat(channel);
        if (!OPENAI_COMPATIBLE.equals(format)) return format;
        if (!hasKey && keyOptional(channel)) return KEYLESS_ADAPTER;
        String vendor = vendor(channel);
        return vendor != null && KNOWN_OPENAI_COMPATIBLE.contains(vendor) ? vendor : OPENAI_COMPATIBLE;
    }

    private static String lower(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
