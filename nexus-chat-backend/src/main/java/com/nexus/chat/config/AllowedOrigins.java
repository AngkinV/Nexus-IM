package com.nexus.chat.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * The one list of browser origins the backend accepts. HTTP CORS, the SockJS
 * endpoint /ws and the native endpoint /ws-native all read it.
 *
 * cors.exclusive-origins, when set, is the whole list: the built-in origins and
 * cors.allowed-origins are dropped, not extended. The funnel profile retains
 * loopback/Electron clients and one published site; the public gateway separately
 * restricts incoming browser origins to that site alone.
 */
@Slf4j
@Component
public class AllowedOrigins {

    private static final List<String> BUILT_IN = List.of(
            "http://localhost:*",
            "http://127.0.0.1:*",
            "https://chat.angkin.cn",
            "https://*.angkin.cn",
            "https://nexus-chat-fwohlcnoevd.qoder.zone",
            "https://nexus-chat-2026-fwohlcnoevd.qoder.zone",
            "app://*"
    );

    private final List<String> patterns;

    public AllowedOrigins(@Value("${cors.allowed-origins:}") String additional,
                          @Value("${cors.exclusive-origins:}") String exclusive) {
        List<String> only = parse(exclusive);
        this.patterns = only.isEmpty()
                ? Stream.concat(BUILT_IN.stream(), parse(additional).stream()).distinct().toList()
                : only;
        log.info("Allowed origins ({}): {}", only.isEmpty() ? "built-in + cors.allowed-origins" : "cors.exclusive-origins only", patterns);
    }

    public List<String> patterns() {
        return patterns;
    }

    private static List<String> parse(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .distinct()
                .toList();
    }
}
