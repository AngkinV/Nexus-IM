package com.nexus.chat.service.agent;

import com.nexus.chat.agent.AgentToolFixture;
import com.nexus.chat.service.agent.tool.AgentToolRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real registry -> real authority -> real service, synthetic repository only. Not executed. */
class AgentScopeReadBoundaryTest {
    @Test void scopedServiceReadsNonemptyAAndNeverReadsBBody() {
        var fixture=AgentToolFixture.create();var registry=new AgentToolRegistry(List.of(fixture.service()));
        var handler=registry.specifications().stream().filter(s->s.tool().name().equals("get_recent_messages")).findFirst().orElseThrow().callHandler();
        var ctx=McpTransportContext.create(Map.of(AgentToolRegistry.CTX_ACTOR,"7",AgentToolRegistry.CTX_SCOPE,fixture.scopeHeader(7)));
        var a=handler.apply(ctx,new McpSchema.CallToolRequest("get_recent_messages",Map.of("chat_id",5L),null));
        assertFalse(Boolean.TRUE.equals(a.isError()));assertTrue(a.content().toString().contains("hi bob"));
        verify(fixture.messages(),times(1)).findScopedMessages(eq(5L),isNull(),isNull(),any());
        var b=handler.apply(ctx,new McpSchema.CallToolRequest("get_recent_messages",Map.of("chat_id",202L),null));
        assertTrue(Boolean.TRUE.equals(b.isError()));assertNull(b.structuredContent());assertFalse(b.content().toString().contains("B_ONLY"));
        verify(fixture.messages(),never()).findScopedMessages(eq(202L),any(),any(),any());verify(fixture.messages(),never()).findById(any());
    }

    /** The actor leaves the chat while the bodies are being read: what was read is not handed back. */
    @Test void membershipLostDuringTheReadReleasesNoBody() {
        var fixture=AgentToolFixture.create();var registry=new AgentToolRegistry(List.of(fixture.service()));
        var page=fixture.messages().findScopedMessages(5L,null,null,org.springframework.data.domain.PageRequest.of(0,80));
        assertFalse(page.getContent().isEmpty());clearInvocations(fixture.messages());
        doAnswer(read->{when(fixture.members().existsByChatIdAndUserId(5L,7L)).thenReturn(false);return page;})
                .when(fixture.messages()).findScopedMessages(eq(5L),any(),any(),any());
        var handler=registry.specifications().stream().filter(s->s.tool().name().equals("get_recent_messages")).findFirst().orElseThrow().callHandler();
        var ctx=McpTransportContext.create(Map.of(AgentToolRegistry.CTX_ACTOR,"7",AgentToolRegistry.CTX_SCOPE,fixture.scopeHeader(7)));
        var result=handler.apply(ctx,new McpSchema.CallToolRequest("get_recent_messages",Map.of("chat_id",5L),null));
        verify(fixture.messages(),times(1)).findScopedMessages(eq(5L),any(),any(),any()); // the read did happen
        assertTrue(Boolean.TRUE.equals(result.isError()));assertNull(result.structuredContent());
        assertFalse(result.content().toString().contains("hi bob"));
    }
}
