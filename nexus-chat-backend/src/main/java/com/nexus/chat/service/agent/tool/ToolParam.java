package com.nexus.chat.service.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A tool argument supplied by the model. Supported Java types: Long/long and
 * Integer/int (schema type {@code integer}), String ({@code string}).
 * An optional argument the model omits is passed as {@code null}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolParam {
    String name();

    String description() default "";

    boolean required() default true;

    String[] enumValues() default {};
}
