package com.nexus.chat.exception;

import lombok.Getter;

/**
 * 业务异常类 - 支持国际化消息键
 */
@Getter
public class BusinessException extends RuntimeException {

    /**
     * 国际化消息键
     */
    private final String messageKey;

    /**
     * 消息参数（用于占位符替换）
     */
    private final Object[] args;

    public BusinessException(String messageKey) {
        super(messageKey);
        this.messageKey = messageKey;
        this.args = null;
    }

    public BusinessException(String messageKey, Object... args) {
        super(messageKey);
        this.messageKey = messageKey;
        this.args = args;
    }

    public BusinessException(String messageKey, Throwable cause) {
        super(messageKey, cause);
        this.messageKey = messageKey;
        this.args = null;
    }

    public BusinessException(String messageKey, Throwable cause, Object... args) {
        super(messageKey, cause);
        this.messageKey = messageKey;
        this.args = args;
    }
}
