package com.nexus.chat.service.agent;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

final class InternalRequestSigner {
    private InternalRequestSigner() {}

    static HttpHeaders headers(String secret, long actor, String trace, String body, AgentProviderService.ResolvedProvider channel) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = timestamp + "." + nonce + "." + hexDigest(digest, body);
            if (channel != null) {
                String base = Objects.toString(channel.baseUrl(), "");
                String model = Objects.toString(channel.defaultModel(), "");
                String key = Base64.getEncoder().encodeToString(Objects.toString(channel.apiKeyPlain(), "").getBytes(StandardCharsets.UTF_8));
                headers.set("X-Model-Provider", channel.provider());
                headers.set("X-Model-Base-URL", base);
                headers.set("X-Model-Name", model);
                headers.set("X-Model-Api-Key", key);
                payload += "." + hexDigest(digest, String.join("|", channel.provider(), base, model, key));
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            headers.set("X-Internal-Signature", HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Internal request signing unavailable", ex);
        }
        headers.set("X-Internal-Service", "nexus-chat-backend");
        headers.set("X-Internal-Timestamp", timestamp);
        headers.set("X-Internal-Nonce", nonce);
        headers.set("X-Trace-Id", trace);
        headers.set("X-Actor-User-Id", Long.toString(actor));
        return headers;
    }

    private static String hexDigest(MessageDigest digest, String text) {
        return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
