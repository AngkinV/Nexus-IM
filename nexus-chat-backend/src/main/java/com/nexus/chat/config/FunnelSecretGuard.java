package com.nexus.chat.config;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Public exposure must not silently reuse the development credentials. */
@Component
@Profile("funnel")
public class FunnelSecretGuard {
    private static final List<String> REQUIRED = List.of("jwt.secret", "agent.internal.token",
            "agent.internal.signing-secret", "companion.api-key.secret");
    private static final Pattern DEFAULT_PLACEHOLDER = Pattern.compile("^\\$\\{[^:}]+:(.*)}$");

    public FunnelSecretGuard(Environment environment) {
        Properties builtIn = new Properties();
        try (InputStream input = FunnelSecretGuard.class.getResourceAsStream("/application.properties")) {
            if (input != null) builtIn.load(input);
        } catch (IOException error) {
            throw new IllegalStateException("Funnel cannot validate its security configuration");
        }
        for (String key : REQUIRED) {
            String value = environment.getProperty(key, "");
            String lower = value.toLowerCase(Locale.ROOT);
            Matcher fallback = DEFAULT_PLACEHOLDER.matcher(builtIn.getProperty(key, ""));
            boolean builtInDefault = fallback.matches() && value.equals(fallback.group(1));
            if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length < 32 || builtInDefault
                    || lower.startsWith("dev-") || lower.contains("-dev-") || lower.contains("change-me") || lower.contains("changeme")) {
                // Never put the value into a log or exception. Changing an encryption
                // key requires migrating already-encrypted credentials, not guessing.
                throw new IllegalStateException("Funnel requires a configured non-development value of at least 32 bytes for " + key);
            }
        }
    }

    public boolean validated() {
        return true;
    }
}
