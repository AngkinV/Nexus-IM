package com.nexus.chat.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.security.AgentInternalTokenFilter;
import com.nexus.chat.service.agent.tool.AgentToolRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes the agent tools as a stateless MCP server (Streamable HTTP, JSON
 * responses) for the Python agent. Tools come from {@link AgentToolRegistry}.
 */
@Configuration
public class AgentMcpConfig {

    public static final String MCP_PATH = "/internal/agent/mcp";

    /** The transport writes its own rejections by serializing the exception; keep the stack out of the response. */
    @JsonIgnoreProperties({"stackTrace", "cause", "suppressed", "localizedMessage"})
    private abstract static class ErrorBodyMixin {
    }

    @Bean
    public HttpServletStatelessServerTransport agentMcpTransport() {
        return HttpServletStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(new ObjectMapper().addMixIn(Throwable.class, ErrorBodyMixin.class)))
                .messageEndpoint(MCP_PATH)
                .contextExtractor(request -> {
                    // McpTransportContext.create copies with Map.copyOf, which rejects null values.
                    Map<String, Object> context = new LinkedHashMap<>();
                    Map.of(AgentToolRegistry.CTX_ACTOR, "X-Actor-User-Id",
                            AgentToolRegistry.CTX_TRACE_ID, "X-Trace-Id",
                            AgentToolRegistry.CTX_SCOPE, "X-Agent-Scope",
                            AgentToolRegistry.CTX_TRACEPARENT, "traceparent"
                    ).forEach((key, header) -> {
                        String value = request.getHeader(header);
                        if (value != null) {
                            context.put(key, value);
                        }
                    });
                    return McpTransportContext.create(context);
                })
                .build();
    }

    @Bean(destroyMethod = "close")
    public McpStatelessSyncServer agentMcpServer(HttpServletStatelessServerTransport agentMcpTransport,
                                                 AgentToolRegistry registry) {
        return McpServer.sync(agentMcpTransport)
                .jsonMapper(new JacksonMcpJsonMapper(new ObjectMapper()))
                .serverInfo("nexus-agent-tools", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                // AgentToolRegistry validates arguments itself and reports failures as toolError JSON.
                .validateToolInputs(false)
                .tools(registry.specifications())
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStatelessServerTransport> agentMcpServlet(
            HttpServletStatelessServerTransport agentMcpTransport) {
        ServletRegistrationBean<HttpServletStatelessServerTransport> bean =
                new ServletRegistrationBean<>(agentMcpTransport, MCP_PATH);
        bean.setName("agentMcp");
        return bean;
    }

    @Bean
    public FilterRegistrationBean<AgentInternalTokenFilter> agentMcpTokenFilter(
            @Value("${agent.internal.token}") String internalToken) {
        FilterRegistrationBean<AgentInternalTokenFilter> bean =
                new FilterRegistrationBean<>(new AgentInternalTokenFilter(internalToken));
        bean.addUrlPatterns(MCP_PATH, "/internal/agent/scope/validate");
        // Ahead of Spring Security and everything else: no JSON-RPC is parsed before the token check.
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}
