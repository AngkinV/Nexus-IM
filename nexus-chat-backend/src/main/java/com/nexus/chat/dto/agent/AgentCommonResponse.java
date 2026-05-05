package com.nexus.chat.dto.agent;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class AgentCommonResponse<T> {
    private String code;
    private String message;
    private String requestId;
    private String timestamp;
    private T data;

    public static <T> AgentCommonResponse<T> ok(String requestId, T data) {
        return AgentCommonResponse.<T>builder()
                .code("OK")
                .message("success")
                .requestId(requestId)
                .timestamp(OffsetDateTime.now().toString())
                .data(data)
                .build();
    }
}
