package com.nexus.chat.dto.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Write-only request; secrets must not enter request logging through toString. */
public final class McpWriteRequest {
    private final ObjectNode data;
    @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
    public McpWriteRequest(ObjectNode data){this.data=data;}
    public ObjectNode payload(){return data;}
    @Override public String toString(){return "McpWriteRequest[redacted]";}
}
