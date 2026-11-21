package com.nexus.chat.service.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method of an {@link AgentToolProvider} bean as a tool exposed to the
 * Python agent over MCP. The method must return a {@code Map}; every parameter
 * must carry either {@link ToolParam} or {@link Actor}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {
    String name();

    String description();
}
