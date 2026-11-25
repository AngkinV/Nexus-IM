package com.nexus.chat.controller;

import com.nexus.chat.config.AllowedOrigins;
import com.nexus.chat.config.FunnelSecretGuard;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local-only readiness contract; the public gateway never proxies /internal/. */
@RestController
@Profile("funnel")
@RequiredArgsConstructor
public class FunnelReadinessController {
    public static final String POLICY_VERSION = "nexus-funnel-v1";
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    private final AllowedOrigins allowedOrigins;
    private final Environment environment;
    private final FunnelSecretGuard secretGuard;

    @GetMapping("/internal/funnel/readiness")
    public ResponseEntity<Map<String, Object>> readiness(HttpServletRequest request) {
        if (!LOOPBACK.contains(request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(Map.of("ready", false));
        }
        List<String> origins = allowedOrigins.patterns();
        String exclusive = environment.getProperty("cors.exclusive-origins", "").trim();
        boolean ready = secretGuard.validated() && "127.0.0.1".equals(environment.getProperty("server.address"))
                && !exclusive.isBlank() && !exclusive.contains("*")
                && exclusive.startsWith("https://") && origins.equals(List.of(exclusive));
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("ready", ready, "policyVersion", POLICY_VERSION, "origins", origins));
    }
}
