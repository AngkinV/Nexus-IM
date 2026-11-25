package com.nexus.chat.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AllowedOriginsTest {
    private static final String QODER = "https://nexus-chat-2026-fwohlcnoevd.qoder.zone";
    private static final List<String> BUILT_IN = List.of("http://localhost:*", "http://127.0.0.1:*", "https://chat.angkin.cn",
            "https://*.angkin.cn", "https://nexus-chat-fwohlcnoevd.qoder.zone", QODER, "app://*");

    @Test
    void withNothingConfiguredTheListIsEveryEarlierOriginPlusTheCurrentQoderSite() {
        assertEquals(BUILT_IN, new AllowedOrigins("", "").patterns());
    }

    @Test
    void configuredOriginsAreTrimmedDeduplicatedAndAppendedAfterTheBuiltInOnes() {
        List<String> patterns = new AllowedOrigins(" https://one.example ,,https://two.example,https://one.example, https://chat.angkin.cn ,", "  ").patterns();

        assertEquals(BUILT_IN, patterns.subList(0, BUILT_IN.size()));
        assertEquals(List.of("https://one.example", "https://two.example"), patterns.subList(BUILT_IN.size(), patterns.size()));
    }

    @Test
    void anExclusiveListReplacesTheBuiltInOriginsAndTheConfiguredAdditions() {
        assertEquals(List.of(QODER), new AllowedOrigins("http://localhost:5173,https://extra.example", " " + QODER + " ,, " + QODER).patterns());
    }
}
