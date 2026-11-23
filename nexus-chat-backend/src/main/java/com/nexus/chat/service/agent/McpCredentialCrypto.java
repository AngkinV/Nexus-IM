package com.nexus.chat.service.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Explicitly configured AES-256-GCM. There is no fixed development key fallback. */
@Service
public class McpCredentialCrypto {
    private static final int NONCE_BYTES = 12;
    private final String keyBase64;
    private final SecureRandom random = new SecureRandom();

    public McpCredentialCrypto(@Value("${agent.mcp.credential-key-base64:}") String keyBase64) {
        this.keyBase64 = keyBase64;
    }

    private SecretKeySpec key() {
        try {
            byte[] bytes = Base64.getDecoder().decode(keyBase64);
            if (bytes.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(bytes, "AES");
        } catch (Exception ignored) {
            throw new IllegalStateException("MCP credential key unavailable");
        }
    }

    public String encrypt(String plaintext, long owner, String serverId) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(binding(owner, serverId));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[NONCE_BYTES + encrypted.length];
            System.arraycopy(nonce, 0, packed, 0, NONCE_BYTES);
            System.arraycopy(encrypted, 0, packed, NONCE_BYTES, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception ignored) {
            throw new IllegalStateException("MCP credential encryption unavailable");
        }
    }

    public String decrypt(String encoded, long owner, String serverId) {
        try {
            byte[] packed = Base64.getDecoder().decode(encoded);
            if (packed.length < NONCE_BYTES + 16) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(packed, 0, NONCE_BYTES)));
            cipher.updateAAD(binding(owner, serverId));
            return new String(cipher.doFinal(Arrays.copyOfRange(packed, NONCE_BYTES, packed.length)), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            throw new IllegalStateException("MCP credential decryption unavailable");
        }
    }

    private byte[] binding(long owner, String serverId) {
        if (owner <= 0 || !java.util.UUID.fromString(serverId).toString().equals(serverId)) throw new IllegalArgumentException();
        return ("nexus-mcp-v1/" + owner + "/" + serverId).getBytes(StandardCharsets.UTF_8);
    }

    @Override public String toString() { return "McpCredentialCrypto[redacted]"; }
}
