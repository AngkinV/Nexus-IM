package com.nexus.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The one place that knows which 3D models exist on disk.
 *
 * Both the asset list the settings panel renders and the tavern's "invite a
 * guest" step read from here, so a model uploaded in settings is immediately
 * available to a character walking in — they used to be two disconnected paths,
 * which is why an invited guest arrived with no model at all.
 *
 * Read-only: uploading and renaming stay in the asset controller.
 */
@Slf4j
@Service
public class CompanionModelAssetCatalog {

    private static final String MODELS_URL_PREFIX = "/models/";
    private static final List<String> MODEL_EXTENSIONS = List.of(".vrm", ".glb", ".gltf", ".fbx");

    @Value("${companion.assets.models-dir:../nexus-chat-frontend/public/models}")
    private String modelsDir;

    /** One model file as both the asset list and the invite step see it. */
    public record ModelAsset(String fileName, String fileUrl, long size, String type) {}

    /** Every loadable model on disk, sorted by name so the order is stable. */
    public List<ModelAsset> list() {
        Path dir = modelsDirPath();
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        try (Stream<Path> stream = Files.list(dir)) {
            List<ModelAsset> found = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                    .filter(this::isModelFile)
                    .sorted()
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        long size = 0L;
                        try {
                            size = Files.size(path);
                        } catch (IOException ignored) {
                            // A file we cannot stat is still a file we can list.
                        }
                        found.add(new ModelAsset(fileName, MODELS_URL_PREFIX + fileName, size, typeOf(fileName)));
                    });
            return found;
        } catch (IOException e) {
            log.warn("Listing companion models failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Whether this URL points at a model that actually exists.
     *
     * Guards the invite path: a client can send any string, and a character
     * carrying a URL to a missing file would render an empty stage with no
     * explanation. Only names directly inside the models directory are accepted —
     * anything with a path separator or traversal segment is rejected.
     */
    public boolean exists(String fileUrl) {
        String fileName = fileNameOf(fileUrl);
        if (fileName == null) return false;
        Path dir = modelsDirPath();
        if (dir == null || !Files.isDirectory(dir)) return false;
        Path candidate = dir.resolve(fileName).normalize();
        // normalize() collapses "..", so the parent check is what stops traversal.
        if (!dir.equals(candidate.getParent())) return false;
        return Files.isRegularFile(candidate) && isModelFile(candidate);
    }

    /** The model type (vrm / glb / gltf / fbx) implied by a URL, or null. */
    public String typeOfUrl(String fileUrl) {
        String fileName = fileNameOf(fileUrl);
        return fileName == null ? null : typeOf(fileName);
    }

    /**
     * Pick a model for a newly invited guest, avoiding the ones already in use
     * when there is a free one left.
     *
     * The tavern should feel like someone new walked in, not like the same face
     * again — so this prefers an unused model, falls back to any model when they
     * are all taken, and returns null when nothing is uploaded. The caller
     * decides what a null means (today: the stage stays empty, as before).
     *
     * @param inUse model URLs already assigned to other characters
     */
    public ModelAsset pickForGuest(java.util.Collection<String> inUse) {
        List<ModelAsset> all = list();
        if (all.isEmpty()) return null;
        List<ModelAsset> free = new ArrayList<>();
        for (ModelAsset asset : all) {
            if (inUse == null || !inUse.contains(asset.fileUrl())) free.add(asset);
        }
        List<ModelAsset> pool = free.isEmpty() ? all : free;
        // Stable pick from the pool: vary by how many are taken, so consecutive
        // invites do not all land on the same face.
        int index = inUse == null ? 0 : Math.floorMod(inUse.size(), pool.size());
        return pool.get(index);
    }

    private Path modelsDirPath() {
        if (modelsDir == null || modelsDir.isBlank()) return null;
        return Paths.get(modelsDir).toAbsolutePath().normalize();
    }

    private boolean isModelFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : MODEL_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    private String typeOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : MODEL_EXTENSIONS) {
            if (lower.endsWith(ext)) return ext.substring(1);
        }
        return "vrm";
    }

    /** "/models/a.vrm" -> "a.vrm"; null for anything that is not a plain name. */
    private String fileNameOf(String fileUrl) {
        if (fileUrl == null) return null;
        String trimmed = fileUrl.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith(MODELS_URL_PREFIX)) {
            trimmed = trimmed.substring(MODELS_URL_PREFIX.length());
        } else if (trimmed.contains("/")) {
            // Only our own models directory is addressable here.
            return null;
        }
        if (trimmed.isEmpty() || trimmed.contains("..")) return null;
        return trimmed;
    }
}
