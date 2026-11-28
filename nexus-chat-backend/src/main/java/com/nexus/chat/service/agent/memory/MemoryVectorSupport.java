package com.nexus.chat.service.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.service.agent.AgentProviderService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Shared bounded vector handling. Channel revisions are case-sensitive, including credentials. */
public final class MemoryVectorSupport {
    private MemoryVectorSupport() {}
    public static String revision(AgentProviderService.ResolvedProvider c) {
        String raw = String.join("\u0000", "v1", Objects.toString(c.id(),""), Objects.toString(c.provider(),""),
                Objects.toString(c.baseUrl(),""), Objects.toString(c.defaultModel(),""), Objects.toString(c.apiKeyPlain(),""));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static List<Double> decode(ObjectMapper mapper, String raw) {
        if (raw == null || raw.length() > 262144) return List.of();
        try {
            JsonNode node = mapper.readTree(raw);
            if (!node.isArray() || node.isEmpty() || node.size() > 8192) return List.of();
            List<Double> out = new ArrayList<>();
            for (JsonNode n : node) { if (!n.isNumber() || !Double.isFinite(n.doubleValue())) return List.of(); out.add(n.doubleValue()); }
            return out;
        } catch (Exception ignored) { return List.of(); }
    }
    public static double cosine(List<Double> a,List<Double> b) {
        if (a.isEmpty() || a.size()!=b.size()) return 0;
        double as=a.stream().mapToDouble(Math::abs).max().orElse(0), bs=b.stream().mapToDouble(Math::abs).max().orElse(0);
        if (as==0 || bs==0 || !Double.isFinite(as) || !Double.isFinite(bs)) return 0;
        double dot=0,aa=0,bb=0;
        for(int i=0;i<a.size();i++) { double x=a.get(i)/as,y=b.get(i)/bs; dot+=x*y;aa+=x*x;bb+=y*y; }
        double result=dot/Math.sqrt(aa*bb); return Double.isFinite(result)?result:0;
    }
    public static double keyword(String text,String query) {
        if (query==null || query.isBlank()) return 0;
        String topic=query.toLowerCase(Locale.ROOT), lower=text.toLowerCase(Locale.ROOT); int hits=0;
        for(int i=0;i+2<=topic.length();i++) if(lower.contains(topic.substring(i,i+2))) hits++;
        return Math.min(.9,hits/10.0);
    }
}
