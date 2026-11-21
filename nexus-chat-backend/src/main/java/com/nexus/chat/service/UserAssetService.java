package com.nexus.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * The authenticated user's model/motion library. Files and metadata live on
 * the server, outside the frontend's public/build directories. Built-in assets
 * are read-only; legacy shared uploads with no trustworthy owner are not claimed.
 * File ids are stable across display-name edits, so renaming cannot break roles.
 */
@Service
@RequiredArgsConstructor
public class UserAssetService {
    private final ObjectMapper mapper;
    @Value("${companion.assets.user-dir:storage/companion-user-assets}")
    private String rootDir;
    @Value("${companion.assets.models-dir:../nexus-chat-frontend/public/models}")
    private String builtInModelsDir;
    @Value("${companion.assets.builtin-models:SpringSnow.vrm,YuukaPajamas6th103.vrm,default.vrm}")
    private String builtInModelNames;
    @Value("${companion.assets.motions-config:../nexus-chat-frontend/public/motions/motions.json}")
    private String builtInMotionsConfig;

    private static final long MAX_BYTES = 64L * 1024 * 1024;
    // A fixed stripe array avoids retaining one lock forever for every user id.
    private final Object[] locks = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();
    private Object lock(Long owner) { return locks[Math.floorMod(owner.hashCode(), locks.length)]; }

    private Path directory(Long owner) throws IOException {
        Path root = Paths.get(rootDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path dir = root.resolve(owner.toString());
        if (Files.isSymbolicLink(dir)) throw new IOException("Invalid asset directory");
        Files.createDirectories(dir);
        return dir;
    }
    private List<Map<String, Object>> read(Long owner) throws IOException {
        Path manifest = directory(owner).resolve("library.json");
        if (!Files.exists(manifest)) return new ArrayList<>();
        return new ArrayList<>(mapper.readValue(manifest.toFile(), new TypeReference<List<Map<String, Object>>>() {}));
    }
    private void save(Long owner, List<Map<String, Object>> entries) throws IOException {
        Path dir = directory(owner);
        Path temporary = Files.createTempFile(dir, ".library-", ".json");
        try {
            mapper.writeValue(temporary.toFile(), entries);
            try { Files.move(temporary, dir.resolve("library.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, dir.resolve("library.json"), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private Map<String, Object> find(List<Map<String, Object>> entries, String id, String kind) {
        if (id == null || !id.matches("[a-f0-9\\-]{36}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found");
        return entries.stream().filter(e -> id.equals(e.get("id")) && kind.equals(e.get("kind"))).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
    }
    private String cleanName(String name) {
        if (name == null || name.isBlank() || name.length() > 120 || name.chars().anyMatch(c -> c < 32)
                || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid asset name");
        }
        return name.trim();
    }
    private Map<String, Object> view(Map<String, Object> entry) {
        Map<String, Object> out = new LinkedHashMap<>(entry);
        out.remove("storedName");
        String id = (String) entry.get("id"), kind = (String) entry.get("kind");
        String url = "/api/companion/user-assets/file/" + kind + "/" + id;
        out.put("fileUrl", url);
        out.put("owned", true);
        if ("motion".equals(kind)) {
            out.put("key", id); out.put("file", url); out.put("uploaded", true);
            out.put("label", entry.get("fileName"));
        }
        return out;
    }

    public List<Map<String, Object>> listModels(Long owner) throws IOException {
        synchronized (lock(owner)) {
            List<Map<String, Object>> result = new ArrayList<>();
            Set<String> knownModels = new HashSet<>(Arrays.asList((builtInModelNames == null ? "" : builtInModelNames).split(",")));
            Path shared = Paths.get(builtInModelsDir).toAbsolutePath().normalize();
            if (Files.isDirectory(shared)) try (var files = Files.list(shared)) {
                files.filter(p -> knownModels.contains(p.getFileName().toString()) && Files.isRegularFile(p) && !Files.isSymbolicLink(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vrm"))
                        .sorted().forEach(p -> result.add(new LinkedHashMap<>(Map.of(
                                "fileName", p.getFileName().toString(), "fileUrl", "/models/" + p.getFileName(), "owned", false))));
            }
            for (var entry : read(owner)) if ("model".equals(entry.get("kind"))) result.add(view(entry));
            return result;
        }
    }
    public Map<String, Object> listMotions(Long owner) throws IOException {
        synchronized (lock(owner)) {
            List<Map<String, Object>> result = new ArrayList<>();
            Path shared = Paths.get(builtInMotionsConfig).toAbsolutePath().normalize();
            if (Files.isRegularFile(shared)) {
                Map<String, Object> config = mapper.readValue(shared.toFile(), new TypeReference<>() {});
                Object motions = config.get("motions");
                if (motions instanceof List<?> list) for (Object motion : list) {
                    if (!(motion instanceof Map<?, ?> raw) || Boolean.TRUE.equals(raw.get("uploaded"))) continue;
                    Map<String, Object> builtIn = new LinkedHashMap<>();
                    raw.forEach((k, v) -> builtIn.put(String.valueOf(k), v));
                    builtIn.put("owned", false); result.add(builtIn);
                }
            }
            for (var entry : read(owner)) if ("motion".equals(entry.get("kind"))) result.add(view(entry));
            return Map.of("default", "walk", "motions", result);
        }
    }
    private void validateContent(String kind, MultipartFile file) throws IOException {
        try (var input = file.getInputStream()) {
            if ("model".equals(kind)) {
                byte[] header = input.readNBytes(20);
                if (header.length != 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid VRM container");
                var ints = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                int magic = ints.getInt(), version = ints.getInt(), size = ints.getInt();
                int jsonBytes = ints.getInt(), chunkType = ints.getInt();
                if (magic != 0x46546c67 || version != 2 || size != file.getSize() || chunkType != 0x4e4f534a
                        || jsonBytes < 2 || jsonBytes > 8 * 1024 * 1024 || jsonBytes > file.getSize() - 20) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid VRM container");
                }
                var json = mapper.readTree(input.readNBytes(jsonBytes));
                if (json == null || (!json.path("extensions").has("VRM") && !json.path("extensions").has("VRMC_vrm"))) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is not a VRM model");
                }
                // Private uploaded VRMs must be self-contained: no authenticated
                // loader request can escape to an external embedded-resource URL.
                for (String list : List.of("buffers", "images")) for (var resource : json.path(list)) {
                    String uri = resource.path("uri").asText("");
                    if (!uri.isEmpty() && !uri.startsWith("data:")) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VRM resources must be embedded");
                    }
                }
            } else {
                String prefix = new String(input.readNBytes(256), java.nio.charset.StandardCharsets.US_ASCII);
                if (!prefix.startsWith("Kaydara FBX Binary") && !prefix.contains("FBXHeaderExtension") && !prefix.startsWith("; FBX")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is not an FBX motion");
                }
            }
        }
    }

    public Map<String, Object> upload(Long owner, String kind, MultipartFile file) throws IOException {
        if (!Set.of("model", "motion").contains(kind) || file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid asset file");
        }
        String name = cleanName(file.getOriginalFilename());
        String ext = "model".equals(kind) ? ".vrm" : ".fbx";
        if (!name.toLowerCase(Locale.ROOT).endsWith(ext)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid asset extension");
        validateContent(kind, file);
        synchronized (lock(owner)) {
            List<Map<String, Object>> entries = read(owner);
            String id = UUID.randomUUID().toString();
            String storedName = id + ext;
            Path target = directory(owner).resolve(storedName);
            boolean committed = false;
            try {
                try (var input = file.getInputStream()) { Files.copy(input, target); }
                if (Files.size(target) > MAX_BYTES) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Asset file too large");
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", id); entry.put("kind", kind); entry.put("fileName", name);
                entry.put("storedName", storedName); entry.put("size", file.getSize());
                entry.put("createdAt", java.time.Instant.now().toString());
                entries.add(entry); save(owner, entries); committed = true;
                return view(entry);
            } finally { if (!committed) Files.deleteIfExists(target); }
        }
    }
    public Map<String, Object> rename(Long owner, String kind, String id, String name) throws IOException {
        synchronized (lock(owner)) {
            List<Map<String, Object>> entries = read(owner);
            Map<String, Object> entry = find(entries, id, kind);
            entry.put("fileName", cleanName(name)); save(owner, entries);
            return view(entry);
        }
    }
    public void deleteMotion(Long owner, String id) throws IOException {
        synchronized (lock(owner)) {
            List<Map<String, Object>> entries = read(owner);
            Map<String, Object> entry = find(entries, id, "motion");
            Path file = directory(owner).resolve((String) entry.get("storedName"));
            // Remove the record before the bytes; readers never see an entry to
            // a half-deleted file. Failed cleanup is retriable, not an ownership leak.
            entries.remove(entry); save(owner, entries); Files.deleteIfExists(file);
        }
    }
    public Path file(Long owner, String kind, String id) throws IOException {
        synchronized (lock(owner)) {
            Map<String, Object> entry = find(read(owner), id, kind);
            Path dir = directory(owner);
            Path path = dir.resolve((String) entry.get("storedName")).normalize();
            if (!path.getParent().equals(dir) || Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found");
            }
            return path;
        }
    }
}
