package com.nexus.chat.service.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.exception.agent.AgentException;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;

/**
 * Turns every {@link AgentTool} method on every {@link AgentToolProvider} bean
 * into an MCP tool: the input schema is derived from the method signature and
 * the call handler injects the actor from the transport context.
 *
 * Invalid declarations fail application startup.
 */
@Slf4j
@Component
public class AgentToolRegistry {

    public static final String CTX_ACTOR = "actorUserId";
    public static final String CTX_TRACE_ID = "traceId";
    public static final String CTX_TRACEPARENT = "traceparent";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchemaValidator VALIDATOR = McpJsonDefaults.getSchemaValidator();
    private static final Set<Class<?>> INTEGER_TYPES = Set.of(Long.class, long.class, Integer.class, int.class);

    private final List<SyncToolSpecification> specifications;

    private record Outcome(String code, McpSchema.CallToolResult result) {
    }

    public AgentToolRegistry(List<AgentToolProvider> providers) {
        // Sorted by name so tools/list is stable across restarts.
        Map<String, SyncToolSpecification> byName = new TreeMap<>();
        for (AgentToolProvider provider : providers) {
            // Annotations live on the target class, not on a CGLIB proxy subclass.
            for (Method method : AopUtils.getTargetClass(provider).getDeclaredMethods()) {
                AgentTool annotation = method.getAnnotation(AgentTool.class);
                if (annotation == null) {
                    continue;
                }
                if (byName.putIfAbsent(annotation.name(), specification(provider, method, annotation)) != null) {
                    throw new IllegalStateException("Duplicate agent tool name: " + annotation.name());
                }
            }
        }
        this.specifications = List.copyOf(byName.values());
        log.info("agent tools registered: {}", byName.keySet());
    }

    public List<SyncToolSpecification> specifications() {
        return specifications;
    }

    private SyncToolSpecification specification(AgentToolProvider provider, Method method, AgentTool annotation) {
        String where = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new IllegalStateException("@AgentTool method must be public: " + where);
        }
        if (!Map.class.isAssignableFrom(method.getReturnType())) {
            throw new IllegalStateException("@AgentTool method must return a Map: " + where);
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        List<BiFunction<Long, Map<String, Object>, Object>> resolvers = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            Class<?> type = parameter.getType();
            if (parameter.isAnnotationPresent(Actor.class)) {
                if (type != Long.class && type != long.class) {
                    throw new IllegalStateException("@Actor parameter must be a Long: " + where);
                }
                resolvers.add((actor, arguments) -> actor);
                continue;
            }
            ToolParam param = parameter.getAnnotation(ToolParam.class);
            if (param == null) {
                throw new IllegalStateException("Parameter needs @ToolParam or @Actor: " + where);
            }
            if (type != String.class && !INTEGER_TYPES.contains(type)) {
                throw new IllegalStateException("Unsupported @ToolParam type " + type.getSimpleName() + ": " + where);
            }
            if (type.isPrimitive() && !param.required()) {
                throw new IllegalStateException("Optional @ToolParam cannot be a primitive: " + where);
            }
            if (properties.containsKey(param.name())) {
                throw new IllegalStateException("Duplicate @ToolParam name " + param.name() + ": " + where);
            }

            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", type == String.class ? "string" : "integer");
            if (!param.description().isEmpty()) {
                property.put("description", param.description());
            }
            if (param.enumValues().length > 0) {
                property.put("enum", List.of(param.enumValues()));
            }
            properties.put(param.name(), property);
            if (param.required()) {
                required.add(param.name());
            }

            // Arguments are validated against the schema before any resolver runs,
            // so a present value already has the declared JSON type.
            String name = param.name();
            resolvers.add(type == String.class
                    ? (actor, arguments) -> arguments.get(name)
                    : type == Long.class || type == long.class
                    ? (actor, arguments) -> arguments.get(name) instanceof Number n ? (Object) n.longValue() : null
                    : (actor, arguments) -> arguments.get(name) instanceof Number n ? (Object) n.intValue() : null);
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(annotation.name())
                .description(annotation.description())
                .inputSchema(schema)
                .build();
        return new SyncToolSpecification(tool, (context, request) -> {
            long started = System.nanoTime();
            Long actor = context.get(CTX_ACTOR) instanceof String header && header.matches("[1-9][0-9]{0,17}")
                    ? Long.valueOf(header)
                    : null;
            Outcome outcome = actor == null
                    ? failure(AgentToolException.Code.NO_ACTOR, "X-Actor-User-Id header is missing or not a positive integer")
                    : invoke(provider, method, schema, resolvers, actor,
                            request.arguments() == null ? Map.of() : request.arguments());
            // Arguments and payloads carry chat content; they are deliberately not logged.
            log.info("agent tool call: tool={} actor={} traceId={} traceparent={} tookMs={} result={}",
                    annotation.name(), actor, context.get(CTX_TRACE_ID), context.get(CTX_TRACEPARENT),
                    (System.nanoTime() - started) / 1_000_000, outcome.code());
            return outcome.result();
        });
    }

    /**
     * Never throws: an exception escaping an MCP handler becomes a JSON-RPC
     * protocol error, which the client cannot treat as an ordinary tool failure.
     */
    private static Outcome invoke(AgentToolProvider provider, Method method, Map<String, Object> schema,
                                  List<BiFunction<Long, Map<String, Object>, Object>> resolvers,
                                  Long actor, Map<String, Object> arguments) {
        try {
            // Validated here rather than by the MCP server, whose own check answers in plain
            // text: a wrong argument must look like every other tool failure to the client.
            JsonSchemaValidator.ValidationResponse validation = VALIDATOR.validate(schema, arguments);
            if (!validation.valid()) {
                return failure(AgentToolException.Code.BAD_ARG, "Invalid arguments: " + validation.errorMessage());
            }
            Object payload = method.invoke(provider, resolvers.stream().map(r -> r.apply(actor, arguments)).toArray());
            return new Outcome("OK", McpSchema.CallToolResult.builder()
                    .structuredContent(payload)
                    .addTextContent(JSON.valueToTree(payload).toString())
                    .build());
        } catch (InvocationTargetException e) {
            return failure(method, e.getCause());
        } catch (Exception e) {
            return failure(method, e);
        }
    }

    private static Outcome failure(Method method, Throwable cause) {
        if (cause instanceof AgentToolException e) {
            return failure(e.getCode(), e.getMessage());
        }
        if (cause instanceof AgentException e) {
            HttpStatus status = e.getCode().getHttpStatus();
            if (status == HttpStatus.FORBIDDEN || status == HttpStatus.UNAUTHORIZED) {
                return failure(AgentToolException.Code.FORBIDDEN, e.getMessage());
            }
        }
        // Details stay in the server log; the model only sees a generic message.
        log.error("agent tool {} failed", method.getName(), cause);
        return failure(AgentToolException.Code.INTERNAL, "Tool call failed");
    }

    private static Outcome failure(AgentToolException.Code code, String message) {
        return new Outcome(code.name(), McpSchema.CallToolResult.builder()
                .isError(true)
                .addTextContent(JSON.createObjectNode().put("toolError", code.name()).put("message", message).toString())
                .build());
    }
}
