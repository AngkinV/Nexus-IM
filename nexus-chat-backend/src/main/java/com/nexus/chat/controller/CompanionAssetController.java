package com.nexus.chat.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/companion/assets")
@RequiredArgsConstructor
public class CompanionAssetController {

    private static final long MAX_FILE_SIZE = 200 * 1024 * 1024; // 200MB

    @Value("${companion.assets.models-dir:../nexus-chat-frontend/public/models}")
    private String modelsDir;

    @Value("${companion.assets.motions-dir:../nexus-chat-frontend/public/motions}")
    private String motionsDir;

    @Value("${companion.assets.motions-config:../nexus-chat-frontend/public/motions/motions.json}")
    private String motionsConfigPath;

    private final ObjectMapper objectMapper;

    private final Object motionLock = new Object();

    @PostMapping("/model")
    public ResponseEntity<Map<String, Object>> uploadModel(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "empty_file"));
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.badRequest().body(Map.of("error", "file_too_large"));
        }
        String originalName = safeName(file.getOriginalFilename());
        String ext = getExtension(originalName);
        if (!".vrm".equalsIgnoreCase(ext)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_extension"));
        }

        try {
            Path dir = ensureDir(modelsDir);
            String base = sanitizeBaseName(stripExtension(originalName));
            String storedName = resolveUniqueFilename(dir, base, ext);
            Path target = dir.resolve(storedName);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            Map<String, Object> response = new HashMap<>();
            response.put("fileUrl", "/models/" + storedName);
            response.put("fileName", storedName);
            response.put("originalName", originalName);
            response.put("uploadedAt", LocalDateTime.now().toString());
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            log.error("Upload model failed: {}", originalName, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "upload_failed"));
        }
    }

    @PostMapping("/motion")
    public ResponseEntity<Map<String, Object>> uploadMotion(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "label", required = false) String label,
            @RequestParam(value = "labelEn", required = false) String labelEn,
            @RequestParam(value = "key", required = false) String key
    ) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "empty_file"));
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.badRequest().body(Map.of("error", "file_too_large"));
        }

        String originalName = safeName(file.getOriginalFilename());
        String ext = getExtension(originalName);
        if (!".fbx".equalsIgnoreCase(ext)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_extension"));
        }

        try {
            Path dir = ensureDir(motionsDir);
            String base = sanitizeBaseName(stripExtension(originalName));
            String storedName = resolveUniqueFilename(dir, base, ext);
            Path target = dir.resolve(storedName);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }

            MotionItem added;
            MotionConfig updated;
            synchronized (motionLock) {
                MotionConfig config = loadMotionConfig();
                List<MotionItem> motions = config.motions == null ? new ArrayList<>() : new ArrayList<>(config.motions);
                String resolvedKey = key != null && !key.isBlank() ? sanitizeKey(key) : sanitizeKey(base);
                resolvedKey = ensureUniqueKey(resolvedKey, motions);
                String finalLabel = label != null && !label.isBlank() ? label : base;
                String finalLabelEn = labelEn != null && !labelEn.isBlank() ? labelEn : finalLabel;
                added = new MotionItem();
                added.key = resolvedKey;
                added.file = storedName;
                added.label = finalLabel;
                added.labelEn = finalLabelEn;
                added.uploaded = true;
                motions.add(added);
                config.motions = motions;
                if (config.defaultKey == null || config.defaultKey.isBlank()) {
                    config.defaultKey = resolvedKey;
                }
                writeMotionConfig(config);
                updated = config;
            }

            Map<String, Object> response = new HashMap<>();
            response.put("fileUrl", "/motions/" + storedName);
            response.put("motion", added);
            response.put("motions", updated.motions);
            response.put("default", updated.defaultKey);
            response.put("uploadedAt", LocalDateTime.now().toString());
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            log.error("Upload motion failed: {}", originalName, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "upload_failed"));
        }
    }

    @PutMapping("/model/rename")
    public ResponseEntity<Map<String, Object>> renameModel(@RequestBody Map<String, String> request) {
        String oldFileName = request.get("oldFileName");
        String newFileName = request.get("newFileName");
        if (oldFileName == null || oldFileName.isBlank() || newFileName == null || newFileName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing_params"));
        }

        String oldExt = getExtension(oldFileName);
        String newBase = sanitizeBaseName(stripExtension(newFileName));
        String newExt = getExtension(newFileName);
        if (newExt.isEmpty()) {
            newExt = oldExt.isEmpty() ? ".vrm" : oldExt;
        }
        String targetName = newBase + newExt;

        try {
            Path dir = Paths.get(modelsDir).toAbsolutePath().normalize();
            Path oldPath = dir.resolve(oldFileName).toAbsolutePath().normalize();
            if (!Files.exists(oldPath)) {
                return ResponseEntity.notFound().build();
            }
            if (!targetName.equals(oldFileName)) {
                targetName = resolveUniqueFilename(dir, newBase, newExt);
                Path newPath = dir.resolve(targetName);
                Files.move(oldPath, newPath, StandardCopyOption.ATOMIC_MOVE);
            }
            Map<String, Object> response = new HashMap<>();
            response.put("oldFileName", oldFileName);
            response.put("newFileName", targetName);
            response.put("oldFileUrl", "/models/" + oldFileName);
            response.put("newFileUrl", "/models/" + targetName);
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            log.error("Rename model failed: {} -> {}", oldFileName, targetName, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "rename_failed"));
        }
    }

    @PutMapping("/motion/{key}/rename")
    public ResponseEntity<Map<String, Object>> renameMotion(
            @PathVariable String key,
            @RequestBody Map<String, String> request) {
        String newLabel = request.get("label");
        if (key == null || key.isBlank() || newLabel == null || newLabel.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing_params"));
        }

        try {
            MotionItem target = null;
            MotionConfig updated;
            synchronized (motionLock) {
                MotionConfig config = loadMotionConfig();
                List<MotionItem> motions = config.motions == null ? new ArrayList<>() : config.motions;
                for (MotionItem item : motions) {
                    if (item != null && key.equals(item.key)) {
                        item.label = newLabel;
                        item.labelEn = newLabel;
                        target = item;
                        break;
                    }
                }
                if (target == null) {
                    return ResponseEntity.notFound().build();
                }
                writeMotionConfig(config);
                updated = config;
            }

            Map<String, Object> response = new HashMap<>();
            response.put("motion", target);
            response.put("motions", updated.motions);
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            log.error("Rename motion failed: {}", key, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "rename_failed"));
        }
    }

    @GetMapping("/models")
    public ResponseEntity<List<Map<String, Object>>> listModels() {
        try {
            Path dir = Paths.get(modelsDir).toAbsolutePath().normalize();
            if (!Files.exists(dir)) {
                return ResponseEntity.ok(new ArrayList<>());
            }
            List<Map<String, Object>> result = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.filter(p -> {
                    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.endsWith(".vrm") || name.endsWith(".glb") || name.endsWith(".gltf") || name.endsWith(".fbx");
                }).sorted().forEach(p -> {
                    Map<String, Object> item = new HashMap<>();
                    String fileName = p.getFileName().toString();
                    item.put("fileName", fileName);
                    item.put("fileUrl", "/models/" + fileName);
                    try {
                        item.put("size", Files.size(p));
                    } catch (IOException ignored) {}
                    result.add(item);
                });
            }
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            log.error("List models failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/motions")
    public ResponseEntity<MotionConfig> listMotions() {
        try {
            MotionConfig config = loadMotionConfig();
            return ResponseEntity.ok(config);
        } catch (IOException e) {
            log.error("Load motions config failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/motion/{key}")
    public ResponseEntity<Map<String, Object>> deleteMotion(@PathVariable String key) {
        if (key == null || key.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing_key"));
        }

        try {
            MotionItem removed = null;
            MotionConfig updated;
            synchronized (motionLock) {
                MotionConfig config = loadMotionConfig();
                List<MotionItem> motions = config.motions == null ? new ArrayList<>() : new ArrayList<>(config.motions);
                List<MotionItem> next = new ArrayList<>();
                for (MotionItem item : motions) {
                    if (item != null && key.equals(item.key)) {
                        removed = item;
                        continue;
                    }
                    next.add(item);
                }
                if (removed == null) {
                    return ResponseEntity.notFound().build();
                }
                config.motions = next;
                if (key.equals(config.defaultKey)) {
                    config.defaultKey = next.isEmpty() ? null : next.get(0).key;
                }
                writeMotionConfig(config);
                updated = config;
            }

            if (removed.file != null && !removed.file.isBlank()) {
                Path filePath = Paths.get(motionsDir).resolve(removed.file).toAbsolutePath().normalize();
                try {
                    Files.deleteIfExists(filePath);
                } catch (IOException e) {
                    log.warn("Failed to delete motion file: {}", filePath, e);
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("removed", removed);
            response.put("motions", updated.motions);
            response.put("default", updated.defaultKey);
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            log.error("Delete motion failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "delete_failed"));
        }
    }

    private MotionConfig loadMotionConfig() throws IOException {
        Path path = Paths.get(motionsConfigPath).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            MotionConfig fallback = defaultMotionConfig();
            writeMotionConfig(fallback);
            return fallback;
        }
        try (InputStream in = Files.newInputStream(path)) {
            MotionConfig config = objectMapper.readValue(in, MotionConfig.class);
            if (config.motions == null) config.motions = new ArrayList<>();
            return config;
        }
    }

    private MotionConfig defaultMotionConfig() {
        MotionConfig config = new MotionConfig();
        config.defaultKey = "walk";
        config.motions = new ArrayList<>();
        MotionItem idle = new MotionItem();
        idle.key = "idle";
        idle.file = "idle.fbx";
        idle.label = "待机";
        idle.labelEn = "Idle";
        config.motions.add(idle);
        MotionItem walk = new MotionItem();
        walk.key = "walk";
        walk.file = "walk.fbx";
        walk.label = "走路";
        walk.labelEn = "Walk";
        config.motions.add(walk);
        MotionItem think = new MotionItem();
        think.key = "think";
        think.file = "think.fbx";
        think.label = "思考";
        think.labelEn = "Think";
        config.motions.add(think);
        return config;
    }

    private void writeMotionConfig(MotionConfig config) throws IOException {
        Path path = Paths.get(motionsConfigPath).toAbsolutePath().normalize();
        Path dir = path.getParent();
        if (dir == null) {
            dir = Paths.get(".").toAbsolutePath().normalize();
        }
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, "motions", ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), config);
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private Path ensureDir(String dir) throws IOException {
        Path path = Paths.get(dir).toAbsolutePath().normalize();
        Files.createDirectories(path);
        return path;
    }

    private String safeName(String name) {
        return name == null ? "file" : name.trim();
    }

    private String stripExtension(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        return idx >= 0 ? filename.substring(0, idx) : filename;
    }

    private String getExtension(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        return idx >= 0 ? filename.substring(idx).toLowerCase(Locale.ROOT) : "";
    }

    private String sanitizeBaseName(String base) {
        if (base == null || base.isBlank()) return "asset";
        String normalized = base.replaceAll("[^a-zA-Z0-9_-]+", "_");
        if (normalized.isBlank()) return "asset";
        return normalized;
    }

    private String sanitizeKey(String key) {
        if (key == null || key.isBlank()) return "motion";
        String normalized = key.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
        return normalized.isBlank() ? "motion" : normalized;
    }

    private String resolveUniqueFilename(Path dir, String base, String ext) {
        String candidate = base + ext;
        int counter = 1;
        while (Files.exists(dir.resolve(candidate))) {
            candidate = base + "-" + counter + ext;
            counter++;
        }
        return candidate;
    }

    private String ensureUniqueKey(String base, List<MotionItem> motions) {
        String candidate = base;
        int counter = 1;
        while (containsKey(motions, candidate)) {
            candidate = base + "_" + counter;
            counter++;
        }
        return candidate;
    }

    private boolean containsKey(List<MotionItem> motions, String key) {
        if (motions == null) return false;
        for (MotionItem item : motions) {
            if (item != null && key.equals(item.key)) return true;
        }
        return false;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MotionConfig {
        @JsonProperty("default")
        public String defaultKey;
        public List<MotionItem> motions;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MotionItem {
        public String key;
        public String file;
        public String label;
        public String labelEn;
        public Boolean uploaded;
    }
}
