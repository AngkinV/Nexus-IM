package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Java's local authority. Transactions never include Python/network requests. */
@Service
public class McpManagementStore {
    private static final Set<String> WRITE_FIELDS = Set.of("displayName", "url", "transport", "enabled",
            "timeoutSec", "resultMaxChars", "allowedTools", "credential", "clearCredential", "expectedConfigVersion");
    private static final List<String> CONFIG_FIELDS = List.of("displayName", "url", "transport", "enabled",
            "timeoutSec", "resultMaxChars", "allowedTools");
    private static final int MAX_SERVERS = 128;
    private static final int MAX_TOOLS = 128;
    private final McpServerRepository repository;
    private final ObjectMapper json;
    private final McpCredentialCrypto crypto;

    public McpManagementStore(McpServerRepository repository, ObjectMapper json, McpCredentialCrypto crypto) {
        this.repository = repository;
        this.json = json;
        this.crypto = crypto;
    }

    private ResponseStatusException bad() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "MCP_INVALID_CONFIG");
    }

    private ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "MCP_CONFIG_CONFLICT");
    }

    private void requireActor(long actor) {
        if (actor <= 0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "MCP_AUTH_REQUIRED");
    }

    public McpServerEntity own(long actor, String id) {
        requireActor(actor);
        return repository.findByServerIdAndOwnerUserIdAndDeletedFalse(id, actor)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MCP_NOT_FOUND"));
    }

    /** Only the authenticated internal registry may read an owner-scoped tombstone. */
    public McpServerEntity internalOwn(long actor, String id) {
        requireActor(actor);
        return repository.findByServerIdAndOwnerUserId(id, actor)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MCP_NOT_FOUND"));
    }

    public void version(McpServerEntity entity, long expected) {
        if (expected < 1 || entity.configVersion != expected) throw conflict();
    }

    private JsonNode read(String text) {
        try {
            return json.readTree(text);
        } catch (Exception ignored) {
            throw new IllegalStateException("MCP stored data unavailable");
        }
    }

    private ObjectNode object(String text) {
        JsonNode value = read(text);
        if (!(value instanceof ObjectNode node)) throw new IllegalStateException("MCP stored data unavailable");
        return node;
    }

    private static boolean ascii(String value) {
        return value.chars().allMatch(c -> c >= 32 && c < 127);
    }

    private void validate(ObjectNode config) {
        String label = config.path("displayName").asText("");
        if (!config.path("displayName").isTextual() || label.isBlank() || label.length() > 80) throw bad();
        if (!"streamable_http".equals(config.path("transport").asText())) throw bad();
        try {
            String url = config.path("url").asText("");
            if (!config.path("url").isTextual() || url.isEmpty() || url.length() > 2048 || !ascii(url)
                    || url.chars().anyMatch(Character::isWhitespace) || url.contains("\\")) throw bad();
            URI endpoint = URI.create(url);
            if (!Set.of("https", "http").contains(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getRawUserInfo() != null || endpoint.getRawFragment() != null
                    || endpoint.getRawQuery() != null) throw bad();
        } catch (Exception ignored) {
            throw bad();
        }
        if (!config.path("enabled").isBoolean() || !config.path("timeoutSec").isIntegralNumber()
                || !config.path("resultMaxChars").isIntegralNumber()
                || !config.path("timeoutSec").canConvertToInt() || !config.path("resultMaxChars").canConvertToInt()
                || config.path("timeoutSec").asInt() < 1 || config.path("timeoutSec").asInt() > 60
                || config.path("resultMaxChars").asInt() < 256 || config.path("resultMaxChars").asInt() > 64000
                || !config.path("allowedTools").isArray() || config.path("allowedTools").size() > MAX_TOOLS) throw bad();
    }

    private void credential(McpServerEntity entity, ObjectNode config, JsonNode input) {
        if (!input.isObject()) throw bad();
        input.fieldNames().forEachRemaining(key -> {
            if (!Set.of("kind", "headerName", "secret").contains(key)) throw bad();
        });
        String kind = input.path("kind").asText("");
        String secret = input.path("secret").asText("");
        if (!Set.of("bearer", "header").contains(kind) || !input.path("secret").isTextual()
                || secret.isBlank() || secret.length() > 4000 || !ascii(secret)) throw bad();
        String header = input.path("headerName").asText("");
        if (kind.equals("header") && (!input.path("headerName").isTextual()
                || !Set.of("x-api-key", "api-key").contains(header.toLowerCase(Locale.ROOT)))) throw bad();
        if (kind.equals("bearer") && input.hasNonNull("headerName")) throw bad();
        entity.credentialCiphertext = crypto.encrypt(input.toString(), entity.ownerUserId, entity.serverId);
        config.put("credentialKind", kind);
        if (kind.equals("header")) config.put("credentialHeaderName", header);
        else config.putNull("credentialHeaderName");
    }

    @Transactional
    public McpServerEntity write(long actor, String id, ObjectNode patch) {
        requireActor(actor);
        if (patch == null || patch.toString().getBytes(StandardCharsets.UTF_8).length > 65536) throw bad();
        patch.fieldNames().forEachRemaining(key -> { if (!WRITE_FIELDS.contains(key)) throw bad(); });
        boolean create = id == null;
        McpServerEntity entity = create ? new McpServerEntity() : own(actor, id);
        if (create) {
            if (patch.has("expectedConfigVersion") || repository.countByOwnerUserIdAndDeletedFalse(actor) >= MAX_SERVERS) throw bad();
            entity.serverId = UUID.randomUUID().toString();
            entity.ownerUserId = actor;
        } else {
            if (!patch.path("expectedConfigVersion").isIntegralNumber()
                    || !patch.path("expectedConfigVersion").canConvertToLong()) throw bad();
            version(entity, patch.path("expectedConfigVersion").asLong());
        }
        ObjectNode old = create ? json.createObjectNode().put("transport", "streamable_http")
                .put("enabled", false).put("timeoutSec", 10).put("resultMaxChars", 8000) : object(entity.configJson);
        if (create) old.set("allowedTools", json.createArrayNode());
        ObjectNode config = old.deepCopy();
        for (String key : CONFIG_FIELDS) if (patch.has(key)) config.set(key, patch.get(key));
        validate(config);
        Set<String> observed = new HashSet<>();
        if (entity.catalogJson != null) for (JsonNode tool : read(entity.catalogJson)) observed.add(tool.path("toolId").asText());
        // Existing approvals may be retained/removed even when a directory was invalidated.
        if (!create) old.path("allowedTools").forEach(tool -> observed.add(tool.asText()));
        Set<String> chosen = new HashSet<>();
        for (JsonNode tool : config.path("allowedTools")) {
            if (!tool.isTextual() || !observed.contains(tool.asText()) || !chosen.add(tool.asText())) throw bad();
        }
        if (patch.has("clearCredential") && !patch.get("clearCredential").isBoolean()) throw bad();
        if (patch.path("clearCredential").asBoolean() && patch.has("credential")) throw bad();
        boolean connectionChanged = !create && (!old.path("url").equals(config.path("url"))
                || !old.path("transport").equals(config.path("transport"))
                || patch.has("credential") || patch.path("clearCredential").asBoolean());
        if (patch.path("clearCredential").asBoolean()) {
            entity.credentialCiphertext = null;
            config.put("credentialKind", "none").putNull("credentialHeaderName");
        }
        if (patch.has("credential")) credential(entity, config, patch.get("credential"));
        if (!config.has("credentialKind")) config.put("credentialKind", "none").putNull("credentialHeaderName");
        if (connectionChanged) {
            config.set("allowedTools", json.createArrayNode());
            entity.catalogJson = null;
            entity.catalogVersion = null;
        }
        if (!create) {
            entity.configVersion++;
            if (connectionChanged || !old.path("enabled").equals(config.path("enabled"))
                    || !old.path("allowedTools").equals(config.path("allowedTools"))) entity.authorizationVersion++;
        }
        entity.configJson = config.toString();
        entity.statusJson = null;
        entity.registrationState = "pending";
        entity.updatedAt = Instant.now();
        return repository.saveAndFlush(entity);
    }

    public ObjectNode unknown(long version) {
        ObjectNode status = json.createObjectNode().put("state", "unknown").put("configVersion", version);
        status.putNull("checkedAt").putNull("latencyMs").putNull("error");
        return status;
    }

    public ObjectNode view(McpServerEntity entity) {
        ObjectNode view = object(entity.configJson);
        view.put("serverId", entity.serverId).put("ownerUserId", entity.ownerUserId)
                .put("namespace", namespace(entity.serverId)).put("configVersion", entity.configVersion)
                .put("authorizationVersion", entity.authorizationVersion)
                .put("credentialConfigured", entity.credentialCiphertext != null)
                .put("registrationState", entity.registrationState)
                .put("createdAt", entity.createdAt.toString()).put("updatedAt", entity.updatedAt.toString());
        if (entity.catalogVersion == null) view.putNull("catalogVersion"); else view.put("catalogVersion", entity.catalogVersion);
        view.set("status", entity.statusJson == null ? unknown(entity.configVersion) : read(entity.statusJson));
        return view;
    }

    public static String namespace(String id) {
        return "srv_" + UUID.fromString(id).toString().replace("-", "");
    }

    public ArrayNode tools(McpServerEntity entity) {
        ArrayNode output = json.createArrayNode();
        Set<String> allowed = new HashSet<>();
        object(entity.configJson).path("allowedTools").forEach(tool -> allowed.add(tool.asText()));
        if (entity.catalogJson != null) for (JsonNode value : read(entity.catalogJson)) {
            ObjectNode tool = value.deepCopy();
            tool.put("allowed", allowed.contains(tool.path("toolId").asText()))
                    .put("timeoutSec", object(entity.configJson).path("timeoutSec").asInt())
                    .put("resultMaxChars", object(entity.configJson).path("resultMaxChars").asInt());
            output.add(tool);
        }
        return output;
    }

    /** Plaintext exists only in the internal signed channel, with a non-printing wrapper. */
    public InternalRecord internal(McpServerEntity entity) {
        ObjectNode record = view(entity);
        record.put("deleted", entity.deleted);
        record.set("tools", tools(entity));
        if (!entity.deleted && entity.credentialCiphertext != null) record.set("credential", read(crypto.decrypt(entity.credentialCiphertext, entity.ownerUserId, entity.serverId)));
        return new InternalRecord(record);
    }

    public static final class InternalRecord {
        private final ObjectNode data;
        InternalRecord(ObjectNode data) { this.data = data; }
        public ObjectNode payload() { return data; }
        @Override public String toString() { return "McpInternalRecord[redacted]"; }
    }

    public List<McpServerEntity> list(long actor) {
        requireActor(actor);
        return repository.findByOwnerUserIdAndDeletedFalseOrderByCreatedAtAsc(actor);
    }

    @Transactional
    public McpServerEntity delete(long actor, String id, long expected) {
        McpServerEntity entity = own(actor, id);
        version(entity, expected);
        ObjectNode config = object(entity.configJson);
        config.put("enabled", false).put("credentialKind", "none").putNull("credentialHeaderName");
        config.set("allowedTools", json.createArrayNode());
        entity.configJson = config.toString();
        entity.deleted = true;
        entity.configVersion++;
        entity.authorizationVersion++;
        entity.credentialCiphertext = null;
        entity.catalogJson = null;
        entity.catalogVersion = null;
        entity.statusJson = null;
        entity.registrationState = "pending";
        entity.updatedAt = Instant.now();
        return repository.saveAndFlush(entity);
    }

    private ObjectNode safeStatus(JsonNode status, long expected) {
        if (!status.isObject() || status.path("configVersion").asLong(-1) != expected
                || !Set.of("connected", "error", "unknown").contains(status.path("state").asText())) throw bad();
        ObjectNode safe = unknown(expected);
        String state = status.path("state").asText();
        safe.put("state", state);
        if (!state.equals("unknown")) {
            try {
                String at = status.path("checkedAt").asText();
                if (at.length() > 128) throw bad();
                Instant.parse(at);
                safe.put("checkedAt", at);
            } catch (Exception ignored) { throw bad(); }
            if (status.path("latencyMs").isIntegralNumber() && status.path("latencyMs").canConvertToLong()
                    && status.path("latencyMs").asLong() >= 0) safe.put("latencyMs", status.path("latencyMs").asLong());
        }
        if (state.equals("error")) safe.set("error", json.createObjectNode()
                .put("code", "MCP_PROBE_FAILED").put("summary", "MCP probe failed"));
        return safe;
    }

    private void rejectSecretEcho(McpServerEntity entity, JsonNode data) {
        if (entity.credentialCiphertext == null) return;
        String secret = read(crypto.decrypt(entity.credentialCiphertext, entity.ownerUserId, entity.serverId)).path("secret").asText();
        String text = data.toString();
        if (secret.isEmpty()) return;
        Set<String> variants = Set.of(secret, Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)),
                HexFormat.of().formatHex(secret.getBytes(StandardCharsets.UTF_8)));
        if (variants.stream().anyMatch(text::contains)) throw bad();
        if (data.isTextual() && data.asText().contains(secret)) throw bad();
        if (data.isContainerNode()) data.forEach(child -> rejectSecretEcho(entity, child));
    }

    private ArrayNode safeCatalog(McpServerEntity entity, JsonNode catalog, JsonNode digest) {
        if (!catalog.isArray() || catalog.size() > MAX_TOOLS
                || catalog.toString().getBytes(StandardCharsets.UTF_8).length > 524288
                || !digest.isTextual() || !digest.asText().matches("[a-f0-9]{64}")) throw bad();
        rejectSecretEcho(entity, catalog);
        ArrayNode output = json.createArrayNode();
        Set<String> ids = new HashSet<>();
        for (JsonNode tool : catalog) {
            String remote = tool.path("remoteName").asText();
            String name;
            try {
                name = namespace(entity.serverId) + "__" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(remote.getBytes(StandardCharsets.UTF_8))).substring(0, 26);
            } catch (Exception ignored) { throw bad(); }
            if (!remote.matches("[A-Za-z0-9_-]{1,64}") || !tool.path("toolId").asText().equals(entity.serverId + ":" + remote)
                    || !tool.path("serverId").asText().equals(entity.serverId) || !name.equals(tool.path("name").asText())
                    || !ids.add(tool.path("toolId").asText()) || !tool.path("inputSchema").isObject()
                    || !"object".equals(tool.path("inputSchema").path("type").asText())
                    || tool.path("inputSchema").toString().getBytes(StandardCharsets.UTF_8).length > 32768
                    || !tool.path("description").isTextual() || tool.path("description").asText().length() > 4096
                    || (tool.hasNonNull("readOnlyHint") && !tool.path("readOnlyHint").isBoolean())) throw bad();
            ObjectNode safe = json.createObjectNode();
            for (String key : List.of("toolId", "serverId", "name", "remoteName", "description", "inputSchema", "readOnlyHint")) {
                if (tool.has(key)) safe.set(key, tool.get(key));
            }
            safe.put("risk", "unclassified").put("allowed", false)
                    .put("timeoutSec", object(entity.configJson).path("timeoutSec").asInt())
                    .put("resultMaxChars", object(entity.configJson).path("resultMaxChars").asInt());
            output.add(safe);
        }
        try {
            if (!McpCatalogDigest.version(output).equals(digest.asText())) throw bad();
        } catch (Exception ignored) { throw bad(); }
        return output;
    }

    @Transactional
    public McpServerEntity applyStatus(long actor, String id, long expected, JsonNode response) {
        return apply(actor, id, expected, response, false);
    }

    /** Config CAS plus @Version prevents an old probe overwriting concurrent edits. */
    @Transactional
    public McpServerEntity apply(long actor, String id, long expected, JsonNode response, boolean probe) {
        McpServerEntity entity = own(actor, id);
        version(entity, expected);
        if (!response.path("ok").asBoolean(false) && !response.has("status")) {
            entity.registrationState = "pending";
            entity.statusJson = null; // No actual handshake evidence: unknown, not connected.
            return repository.saveAndFlush(entity);
        }
        if (!id.equals(response.path("serverId").asText()) || response.path("configVersion").asLong(-1) != expected) throw conflict();
        entity.registrationState = "registered";
        if (response.has("status")) {
            ObjectNode status = safeStatus(response.path("status"), expected);
            if (probe && response.path("ok").asBoolean(false) && !status.path("state").asText().equals("connected")) throw bad();
            // A genuine failed probe is persisted as error even when ok=false.
            entity.statusJson = status.toString();
            if (probe && status.path("state").asText().equals("connected")) {
                // Full JSON Schema validation belongs to Python, not a Java MCP client.
                // Bind that versioned validation proof to the independently checked catalog digest.
                if (!"draft2020-12-local-ref-v1".equals(response.path("catalogValidation").asText())) throw bad();
                ArrayNode tools = safeCatalog(entity, response.path("tools"), response.path("catalogVersion"));
                entity.catalogJson = tools.toString();
                entity.catalogVersion = response.path("catalogVersion").asText();
            }
        } else if (probe) throw bad();
        entity.updatedAt = Instant.now();
        return repository.saveAndFlush(entity);
    }
}
