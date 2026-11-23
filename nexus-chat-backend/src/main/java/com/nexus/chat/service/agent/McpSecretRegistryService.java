package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexus.chat.model.ModelCredential;
import com.nexus.chat.repository.ModelCredentialRepository;
import com.nexus.chat.service.CompanionCryptoService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Read-only quarantine input for trusted Python; never a model API call or public DTO. */
@Service
public class McpSecretRegistryService {
    private final ModelCredentialRepository credentials;
    private final CompanionCryptoService crypto;
    private final ObjectMapper json;

    public McpSecretRegistryService(ModelCredentialRepository credentials, CompanionCryptoService crypto, ObjectMapper json) {
        this.credentials = credentials;
        this.crypto = crypto;
        this.json = json;
    }

    public ProviderSecrets owned(long actor) {
        try {
            if (actor <= 0) throw new IllegalArgumentException();
            ArrayNode result = json.createArrayNode();
            var rows = credentials.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(actor);
            if (rows.size() > 256) throw new IllegalArgumentException();
            for (ModelCredential row : rows) {
                if (row.getUserId() == null || row.getUserId() != actor) throw new IllegalArgumentException();
                String ciphertext = row.getApiKeyEncrypted();
                if (ciphertext == null || ciphertext.isBlank()) continue;
                String value = crypto.decrypt(ciphertext);
                if (value == null || value.isBlank() || value.length() > 16384) throw new IllegalArgumentException();
                result.add(value);
            }
            return new ProviderSecrets(result);
        } catch (Exception ignored) {
            // Unknown/decryption-failed secrets cannot be silently skipped before external IO.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MCP_SECRET_REGISTRY_UNAVAILABLE");
        }
    }

    public static final class ProviderSecrets {
        private final ArrayNode values;
        private ProviderSecrets(ArrayNode values) { this.values = values; }
        public ArrayNode payload() { return values.deepCopy(); }
        @Override public String toString() { return "McpProviderSecrets[redacted]"; }
    }
}
