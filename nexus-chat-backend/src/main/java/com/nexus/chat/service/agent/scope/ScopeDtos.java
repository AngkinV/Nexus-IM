package com.nexus.chat.service.agent.scope;

import java.time.*;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;

public final class ScopeDtos {
    private ScopeDtos() {}
    public record Target(String type, Long id) {
        public Target { if (!("group".equals(type)||"contact".equals(type)) || id==null || id<1) throw new IllegalArgumentException("Invalid target"); }
    }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record TimeRange(@JsonFormat(shape=JsonFormat.Shape.STRING) Instant fromInclusive,
                            @JsonFormat(shape=JsonFormat.Shape.STRING) Instant toExclusive, String timeZone) {
        public TimeRange {
            if((fromInclusive!=null&&fromInclusive.getNano()%1_000_000!=0)||(toExclusive!=null&&toExclusive.getNano()%1_000_000!=0))
                throw new IllegalArgumentException("Scope v1 requires millisecond precision");
            timeZone = timeZone == null || timeZone.isBlank() ? "Asia/Shanghai" : timeZone;
            timeZone = ZoneId.of(timeZone).getId();
            if (fromInclusive != null && toExclusive != null && !fromInclusive.isBefore(toExclusive))
                throw new IllegalArgumentException("Invalid closed-open scope window");
        }
        public static TimeRange unbounded() { return new TimeRange(null,null,"Asia/Shanghai"); }
        // Compatibility helpers use the trusted default, NEVER the display timezone.
        public LocalDateTime fromLocal() { return fromLocal(ZoneId.of("Asia/Shanghai")); }
        public LocalDateTime toLocal() { return toLocal(ZoneId.of("Asia/Shanghai")); }
        public LocalDateTime fromLocal(ZoneId storageZone) { return fromInclusive == null ? null : LocalDateTime.ofInstant(fromInclusive,storageZone); }
        public LocalDateTime toLocal(ZoneId storageZone) { return toExclusive == null ? null : LocalDateTime.ofInstant(toExclusive,storageZone); }
    }
    public record Selection(List<Target> targets, TimeRange timeRange) {
        public Selection { if(targets==null||timeRange==null||targets.size()>200) throw new IllegalArgumentException("Invalid selection"); targets=List.copyOf(targets); }
    }
    public record Binding(long bindingVersion, long sessionGeneration, Selection selection) {}
    public record Update(Long expectedBindingVersion, Selection selection) {}
    public record AuthorizedScope(int schemaVersion, String grantId, Long actorUserId, String sessionId,
        String runId, long bindingVersion, long authorizationVersion, long sessionGeneration,
        List<Long> chatIds, List<Long> contactUserIds, TimeRange timeRange, String scopeKey) {
        public AuthorizedScope {
            if(schemaVersion!=1||grantId==null||!grantId.matches("[A-Za-z0-9_-]{1,64}")||actorUserId==null||actorUserId<1
                ||sessionId==null||!sessionId.matches("[A-Za-z0-9_-]{1,64}")||runId==null||!runId.matches("[A-Za-z0-9_-]{1,64}")
                ||bindingVersion<1||authorizationVersion<1||sessionGeneration<1||timeRange==null||scopeKey==null||!scopeKey.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid scope");
            chatIds=validIds(chatIds); contactUserIds=validIds(contactUserIds);
        }
    }
    private static List<Long> validIds(List<Long> ids) {
        if(ids==null||ids.size()>200||ids.stream().anyMatch(i->i==null||i<1)||ids.stream().distinct().count()!=ids.size()) throw new IllegalArgumentException("Invalid scope ids");
        return List.copyOf(ids);
    }
    /** Optional already-committed call being reused; absent for a plain scope check. */
    public record ToolCall(String name, java.util.Map<String, Object> arguments) {
        public ToolCall {
            if (name == null || name.isBlank() || name.length() > 200 || arguments == null) throw new IllegalArgumentException("Invalid tool call");
            // Keep nulls so a malformed argument is rejected by the guard, not by deserialization.
            arguments = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(arguments));
        }
    }
    public record Validation(Long actorUserId, String sessionId, String runId, AuthorizedScope scope, ToolCall tool) {
        public Validation {
            if (actorUserId == null || sessionId == null || runId == null || scope == null) throw new IllegalArgumentException("Invalid validation request");
        }
    }
}
