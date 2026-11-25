package com.nexus.chat.config;

import ch.qos.logback.classic.BasicConfigurator;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The real Spring-aware Logback configuration, without starting any service. */
class FunnelLogbackConfigurationTest {
    @TempDir Path directory;

    private record Output(String console, String errorFile) {}

    private Output logFailure(String[] profiles) throws Exception {
        Path logs = directory.resolve(String.join("-", profiles));
        Files.createDirectories(logs);
        String previousPath = System.getProperty("LOG_PATH");
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        LoggingSystem system = LoggingSystem.get(getClass().getClassLoader());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setProperty("LOG_PATH", logs.toString());
            context.reset();
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profiles);
            environment.withProperty("jwt.secret", "fixture-only-not-for-use-outside-tests-jwt");
            system.beforeInitialize();
            system.initialize(new LoggingInitializationContext(environment), "classpath:logback-spring.xml", null);
            Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            assertNotNull(root.getAppender("CONSOLE"), "A startup exception must not disappear for " + String.join(",", profiles));
            @SuppressWarnings("unchecked")
            ConsoleAppender<ILoggingEvent> console = (ConsoleAppender<ILoggingEvent>) root.getAppender("CONSOLE");
            // Capture the real encoder's output without rebinding global System.out.
            console.setOutputStream(bytes);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new FunnelSecretGuard(environment));
            context.getLogger("org.springframework.boot.SpringApplication").error("Application run failed", failure);
            context.stop(); // Flush the real asynchronous file sink before reading.
            Path errorFile = logs.resolve("nexus-chat-error.log");
            return new Output(bytes.toString(StandardCharsets.UTF_8), Files.exists(errorFile) ? Files.readString(errorFile) : "");
        } finally {
            system.cleanUp();
            context.stop();
            context.reset();
            BasicConfigurator fallback = new BasicConfigurator();
            fallback.setContext(context);
            fallback.configure(context);
            context.start();
            if (previousPath == null) System.clearProperty("LOG_PATH");
            else System.setProperty("LOG_PATH", previousPath);
        }
    }

    @Test
    void localAndFunnelFailuresReachTheConsoleAndErrorFileWithEveryInvalidKey() throws Exception {
        Output output = logFailure(new String[]{"local", "funnel"});
        for (String text : new String[]{"Application run failed", "IllegalStateException", "agent.internal.token",
                "agent.internal.signing-secret", "companion.api-key.secret"}) {
            assertTrue(output.console().contains(text), "Console missing " + text);
            assertTrue(output.errorFile().contains(text), "File missing " + text);
        }
        assertFalse(output.console().contains("fixture-only-not-for-use-outside-tests-jwt"));
        assertFalse(output.errorFile().contains("fixture-only-not-for-use-outside-tests-jwt"));
    }

    @Test
    void existingAndUnknownProfilesAlwaysHaveConsoleOutput() throws Exception {
        for (String[] profiles : new String[][]{{"default"}, {"dev"}, {"prod"}, {"local"}, {"preview"}}) {
            assertTrue(logFailure(profiles).console().contains("Application run failed"));
        }
    }

    @Test
    void springProfileConfigurationDoesNotUseTheUnsupportedScanReload() throws Exception {
        try (var input = getClass().getResourceAsStream("/logback-spring.xml")) {
            assertNotNull(input);
            String configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(configuration.contains("scan=\"true\""));
        }
    }
}
