package com.nexus.chat.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class FunnelSecretGuardTest {
    private static final List<String> KEYS = List.of("jwt.secret", "agent.internal.token",
            "agent.internal.signing-secret", "companion.api-key.secret");

    private MockEnvironment configured() {
        MockEnvironment environment = new MockEnvironment();
        for (String key : KEYS) environment.withProperty(key, "fixture-only-not-for-use-outside-tests-" + key);
        return environment;
    }

    @Test
    void explicitlyConfiguredNonDevelopmentValuesAllowPublicStartup() {
        assertTrue(new FunnelSecretGuard(configured()).validated());
    }

    @Test
    void everyRequiredCredentialMustBeConfiguredWithoutEchoingItsValue() {
        for (String key : KEYS) {
            MockEnvironment environment = configured().withProperty(key, "");
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> new FunnelSecretGuard(environment));
            assertTrue(error.getMessage().contains(key));
        }
    }

    @Test
    void shortAndDevelopmentValuesAreRejectedWithoutSecretMaterialInTheError() {
        for (String value : List.of("synthetic-short-value", "dev-" + "x".repeat(40), "change-me-" + "x".repeat(40))) {
            MockEnvironment environment = configured().withProperty("agent.internal.signing-secret", value);
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> new FunnelSecretGuard(environment));
            assertTrue(error.getMessage().contains("agent.internal.signing-secret"));
            assertFalse(error.getMessage().contains(value));
        }
    }

    @Test
    void allInvalidKeysAreReportedTogetherWhileValidKeysAreOmitted() {
        MockEnvironment environment = configured();
        for (String key : KEYS.subList(1, KEYS.size())) environment.withProperty(key, "");
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new FunnelSecretGuard(environment));
        for (String key : KEYS.subList(1, KEYS.size())) assertTrue(error.getMessage().contains(key));
        assertFalse(error.getMessage().contains("jwt.secret"));
        assertFalse(error.getMessage().contains("fixture-only-not-for-use-outside-tests"));
    }

    @Test
    void theTrackedDefaultCannotBecomeThePublicJwtSigningKey() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/application.properties")) {
            assertNotNull(input);
            properties.load(input);
        }
        var matcher = Pattern.compile("^\\$\\{[^:}]+:(.*)}$").matcher(properties.getProperty("jwt.secret"));
        assertTrue(matcher.matches());
        String fallback = matcher.group(1);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new FunnelSecretGuard(configured().withProperty("jwt.secret", fallback)));
        assertTrue(error.getMessage().contains("jwt.secret"));
        assertFalse(error.getMessage().contains(fallback));
    }
}
