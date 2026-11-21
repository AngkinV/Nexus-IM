package com.nexus.chat.agent;

import com.nexus.chat.config.AgentMcpConfig;
import com.nexus.chat.service.agent.AgentToolService;
import com.nexus.chat.service.agent.tool.Actor;
import com.nexus.chat.service.agent.tool.AgentTool;
import com.nexus.chat.service.agent.tool.AgentToolProvider;
import com.nexus.chat.service.agent.tool.AgentToolRegistry;
import com.nexus.chat.service.agent.tool.ToolParam;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Stand-alone MCP endpoint for cross-language checks against the Python client.
 * Embedded Tomcat with only the MCP servlet, its token filter and the tools —
 * no MySQL, no Redis. Data is {@link AgentToolFixture}; {@code echo_sample} is
 * an extra, test-only tool.
 *
 * <pre>
 * java -Dmcp.harness.port=18099 -Dmcp.harness.token=... \
 *      -cp target/classes:target/test-classes:$(cat target/mcp-harness.classpath) \
 *      com.nexus.chat.agent.McpHarnessMain
 * </pre>
 *
 * Listens on 127.0.0.1 and prints {@code MCP_HARNESS_READY} once it accepts requests.
 */
public class McpHarnessMain {

    @Configuration
    // The placeholder configurer resolves ${agent.internal.token} for the filter, which Tomcat creates early.
    @ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, ServletWebServerFactoryAutoConfiguration.class})
    @Import({AgentMcpConfig.class, AgentToolRegistry.class})
    public static class Harness {
        @Bean
        public AgentToolService agentToolService() {
            return AgentToolFixture.create().service();
        }

        @Bean
        public EchoSample echoSample() {
            return new EchoSample();
        }
    }

    public static class EchoSample implements AgentToolProvider {
        @AgentTool(name = "echo_sample", description = "Test-only tool: returns the text it was given and who asked.")
        public Map<String, Object> echo(@Actor Long actorUserId,
                                        @ToolParam(name = "text", description = "Text to return") String text) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("actorUserId", actorUserId);
            body.put("text", text);
            return body;
        }
    }

    public static void main(String[] args) {
        String port = Objects.requireNonNull(System.getProperty("mcp.harness.port"), "-Dmcp.harness.port is required");
        String token = Objects.requireNonNull(System.getProperty("mcp.harness.token"), "-Dmcp.harness.token is required");
        // Command-line arguments outrank application.properties, which pins server.port to the real backend's.
        new SpringApplication(Harness.class).run(
                "--server.address=127.0.0.1",
                "--server.port=" + port,
                "--agent.internal.token=" + token,
                "--logging.file.path=target/mcp-harness-logs",
                "--spring.main.banner-mode=off");
        System.out.println("MCP_HARNESS_READY");
    }
}
