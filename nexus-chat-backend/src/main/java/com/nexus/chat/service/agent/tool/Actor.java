package com.nexus.chat.service.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The id of the user the agent is acting for. Injected from the
 * {@code X-Actor-User-Id} request header and never part of the tool's input
 * schema, so the model cannot choose it.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Actor {
}
