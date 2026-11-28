package com.nexus.chat.service.skills;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** One parser for imported packages and workbench drafts. Text is never executed or fetched. */
@Component
public class SkillPackageValidator {
    public static final int MAX_FILES = 32;
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_FILE_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> PATCH_FIELDS = Set.of("traits", "tone", "baselineMood", "name", "background", "greeting");
    private static final Pattern SECRET = Pattern.compile("sk-[A-Za-z0-9_-]{24,}|AKIA[A-Z0-9]{16}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----");

    public record Parsed(String name, String description, String type, String packageVersion,
                         Map<String, String> patch, List<String> triggers, Map<String, String> files,
                         String instructions, String canonicalJson, String hash) {}

    public JsonNode readJson(String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES * 2) {
            throw invalid("Skill package exceeds the size limit");
        }
        try {
            JsonNode node = JSON.readTree(raw);
            if (node == null || !node.isObject()) throw invalid("Expected a JSON object");
            return node;
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw invalid("Invalid skill JSON"); }
    }

    public Parsed parse(String raw) { return parse(readJson(raw)); }

    public Parsed parse(JsonNode root) {
        JsonNode fileNode = root == null ? null : root.get("files");
        if (fileNode == null || !fileNode.isObject() || fileNode.isEmpty() || fileNode.size() > MAX_FILES) {
            throw invalid("Package must contain a bounded files object");
        }
        TreeMap<String, String> files = new TreeMap<>();
        int bytes = 0;
        Iterator<Map.Entry<String, JsonNode>> entries = fileNode.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            String path = entry.getKey();
            validatePath(path);
            if (!entry.getValue().isTextual()) throw invalid("Only UTF-8 text files are supported");
            String text = entry.getValue().textValue().replace("\r\n", "\n");
            if (text.indexOf('\0') >= 0 || SECRET.matcher(text).find()) {
                throw invalid("Binary content or credentials are not allowed in a skill package");
            }
            int size = text.getBytes(StandardCharsets.UTF_8).length;
            bytes += size;
            if (size > MAX_FILE_BYTES || bytes > MAX_BYTES) throw invalid("Skill package exceeds the size limit");
            String folded = path.toLowerCase(Locale.ROOT);
            if (files.keySet().stream().anyMatch(p -> p.toLowerCase(Locale.ROOT).equals(folded))) {
                throw invalid("Duplicate file paths are not supported");
            }
            files.put(path, text);
        }
        String main = files.get("SKILL.md");
        if (main == null || !main.startsWith("---\n")) throw invalid("SKILL.md must have YAML frontmatter");
        int end = main.indexOf("\n---\n", 4);
        if (end < 0) throw invalid("Missing frontmatter delimiter");
        Map<?, ?> metadata;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setNestingDepthLimit(12);
            options.setCodePointLimit(16 * 1024);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            String header = main.substring(4, end);
            // LoaderOptions limits collection aliases only; scalar aliases need this explicit gate.
            for (var event : yaml.parse(new java.io.StringReader(header))) {
                if (event instanceof org.yaml.snakeyaml.events.AliasEvent) throw invalid("YAML aliases are not supported in skills");
            }
            Object loaded = yaml.load(header);
            if (!(loaded instanceof Map<?, ?> map)) throw invalid("Frontmatter must be a mapping");
            metadata = map;
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw invalid("Invalid or unsafe skill frontmatter"); }
        String name = requiredText(metadata.get("name"), 80, "name");
        String description = requiredText(metadata.get("description"), 1200, "description");
        for (String key : List.of("allowed-tools", "tools", "requires", "permissions", "scripts", "agent", "context")) {
            if (nonempty(metadata.get(key))) throw invalid("This skill requires unsupported execution or permissions");
        }
        Map<?, ?> nexus = Map.of();
        if (metadata.containsKey("nexus")) {
            if (!(metadata.get("nexus") instanceof Map<?, ?> map)) throw invalid("nexus must be a mapping");
            nexus = map;
        }
        Set<String> allowed = Set.of("type", "version", "triggers", "patch", "permissions", "requires");
        for (Object key : nexus.keySet()) if (!allowed.contains(String.valueOf(key))) throw invalid("Unsupported Nexus skill declaration");
        if (nonempty(nexus.get("permissions")) || nonempty(nexus.get("requires"))) {
            throw invalid("This skill requires unsupported execution or permissions");
        }
        String type = nexus.get("type") == null ? "interaction" : requiredText(nexus.get("type"), 30, "type");
        if (!Set.of("personality", "interaction").contains(type)) throw invalid("Unknown skill type");
        String version = nexus.get("version") == null ? "1" : shortScalar(nexus.get("version"), 40, "version");
        LinkedHashMap<String, String> patch = new LinkedHashMap<>();
        if (nexus.containsKey("patch")) {
            if (!(nexus.get("patch") instanceof Map<?, ?> map)) throw invalid("patch must be a mapping");
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!PATCH_FIELDS.contains(key)) throw invalid("Unsupported persona patch field");
                patch.put(key, requiredText(entry.getValue(), 2000, "patch"));
            }
        }
        ArrayList<String> triggers = new ArrayList<>();
        if (nexus.containsKey("triggers")) {
            if (!(nexus.get("triggers") instanceof List<?> list) || list.size() > 12) throw invalid("triggers must be a bounded list");
            for (Object value : list) triggers.add(requiredText(value, 80, "trigger"));
        }
        String instructions = main.substring(end + 5).trim();
        if (instructions.isBlank()) throw invalid("Skill instructions are empty");
        try {
            String canonical = JSON.writeValueAsString(Map.of("files", files));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            return new Parsed(name, description, type, version, Map.copyOf(patch), List.copyOf(triggers),
                    Collections.unmodifiableMap(files), instructions, canonical, hash);
        } catch (Exception e) { throw invalid("Cannot encode skill package"); }
    }

    private static void validatePath(String path) {
        if (path == null || path.length() > 180 || path.indexOf('\\') >= 0 || path.indexOf('%') >= 0
                || path.indexOf(':') >= 0 || path.indexOf('\0') >= 0 || path.startsWith("/") || path.endsWith("/")) {
            throw invalid("Unsafe skill path");
        }
        for (String part : path.split("/", -1)) if (part.isBlank() || part.startsWith(".")) throw invalid("Unsafe skill path");
        if (path.equals("SKILL.md")) return;
        if (!(path.startsWith("references/") || path.startsWith("examples/"))
                || !path.toLowerCase(Locale.ROOT).matches(".*\\.(md|txt|json|yaml|yml)$")) {
            throw invalid("Only SKILL.md and text references/examples are supported; scripts are not supported");
        }
    }

    private static boolean nonempty(Object value) {
        if (value == null || Boolean.FALSE.equals(value)) return false;
        if (value instanceof Collection<?> c) return !c.isEmpty();
        if (value instanceof Map<?, ?> m) return !m.isEmpty();
        return !String.valueOf(value).isBlank();
    }
    private static String shortScalar(Object value, int max, String field) {
        if (!(value instanceof String || value instanceof Number)) throw invalid("Invalid " + field);
        return requiredText(String.valueOf(value), max, field);
    }
    private static String requiredText(Object value, int max, String field) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max) throw invalid("Invalid skill " + field);
        return text.trim();
    }
    private static ResponseStatusException invalid(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
}
