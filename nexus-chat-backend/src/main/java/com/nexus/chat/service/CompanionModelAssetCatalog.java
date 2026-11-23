package com.nexus.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
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
 *
 * Errors carry a stable code as the reason, which the global handler returns as
 * the {@code message} of the error body.
 */
@Slf4j
@Service
public class CompanionModelAssetCatalog {

    /** 400: the value is not a plain file name inside the models directory. */
    public static final String MODEL_URL_INVALID = "model_url_invalid";
    /** 400: a real model type, but not one the tavern stage can show. */
    public static final String MODEL_TYPE_UNSUPPORTED = "model_type_unsupported";
    /** 400: no such file in the models directory. */
    public static final String MODEL_NOT_FOUND = "model_not_found";
    /** 400: the file is there but is not a binary glTF container. */
    public static final String MODEL_FILE_INVALID = "model_file_invalid";
    /** 500: the models directory could not be read — not the same as empty. */
    public static final String CATALOG_UNAVAILABLE = "model_catalog_unavailable";

    /** The only model type the tavern stage loads. */
    public static final String STAGE_TYPE = "vrm";

    private static final String MODELS_URL_PREFIX = "/models/";
    /**
     * File extension to the model type the frontend loaders switch on. GLB is
     * binary glTF and goes through the same loader, so both map to "gltf".
     */
    private static final Map<String, String> MODEL_TYPES =
            Map.of(".vrm", STAGE_TYPE, ".glb", "gltf", ".gltf", "gltf", ".fbx", "fbx");

    private static final int GLB_MAGIC = 0x46546C67; // "glTF", little-endian
    private static final int GLB_JSON_CHUNK = 0x4E4F534A; // "JSON", little-endian
    private static final int GLB_HEADER_BYTES = 20; // file header + first chunk header

    @Value("${companion.assets.models-dir:../nexus-chat-frontend/public/models}")
    private String modelsDir;

    /**
     * One model file as both the asset list and the invite step see it.
     *
     * @param stageReady the tavern stage can load it: a VRM with an addressable
     *                   name whose bytes open as a binary glTF container
     */
    public record ModelAsset(String fileName, String fileUrl, long size, String type, boolean stageReady) {}

    /**
     * Every model file directly inside the models directory, sorted by name.
     *
     * A directory that does not exist yet is an empty catalogue — the first
     * upload creates it. Anything else that stops the read (not a directory, no
     * permission, a file that cannot be opened) is a 500 with
     * {@link #CATALOG_UNAVAILABLE}, so "nothing uploaded" and "could not look"
     * never produce the same answer.
     *
     * Symbolic links are never listed: a link inside the directory could point
     * anywhere on the machine.
     */
    public List<ModelAsset> list() {
        if (modelsDir == null || modelsDir.isBlank()) return List.of();
        Path dir = Paths.get(modelsDir).toAbsolutePath().normalize();
        if (Files.notExists(dir)) return List.of();
        try (Stream<Path> stream = Files.list(dir)) {
            List<ModelAsset> found = new ArrayList<>();
            for (Path path : stream.sorted().toList()) {
                String fileName = path.getFileName().toString();
                String type = typeOf(fileName);
                if (type == null) continue;
                BasicFileAttributes attributes =
                        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile()) continue;
                String fileUrl = MODELS_URL_PREFIX + fileName;
                boolean stageReady = false;
                if (STAGE_TYPE.equals(type) && fileUrl.equals(canonicalUrl(fileUrl))) {
                    try {
                        stageReady = isGlbContainer(path);
                    } catch (IOException e) {
                        // One unreadable candidate must not hide every usable
                        // model. Directory-level failures still propagate below.
                        log.warn("A companion model could not be inspected: {}", fileName);
                    }
                }
                found.add(new ModelAsset(fileName, fileUrl, attributes.size(), type, stageReady));
            }
            return found;
        } catch (IOException | UncheckedIOException e) {
            log.warn("Reading companion models failed: {}", e.toString());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, CATALOG_UNAVAILABLE);
        }
    }

    /** The models an invited guest can actually wear on the tavern stage. */
    public List<ModelAsset> listForStage() {
        return list().stream().filter(ModelAsset::stageReady).toList();
    }

    /**
     * The one form a model reference is stored and compared in:
     * {@code /models/<file name>}. Null for anything that is not a plain model
     * file name directly inside the models directory.
     *
     * Accepts the URL form or a bare file name, so "a.vrm" and "/models/a.vrm"
     * are the same model. Rejects path separators (either slash), traversal,
     * percent-encoding (a decoded "%2F" would be a separator), remote URLs,
     * query strings and fragments. This only judges the text — whether the file
     * is there is {@link #exists}.
     */
    public String canonicalUrl(String fileUrl) {
        if (fileUrl == null) return null;
        String trimmed = fileUrl.trim();
        String name = trimmed.startsWith(MODELS_URL_PREFIX)
                ? trimmed.substring(MODELS_URL_PREFIX.length()) : trimmed;
        if (name.isEmpty() || name.startsWith(".") || name.contains("..")) return null;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F || "/\\%:?#".indexOf(c) >= 0) return null;
        }
        return typeOf(name) == null ? null : MODELS_URL_PREFIX + name;
    }

    /**
     * Whether this reference names a model file that is really in the directory.
     * Matched against the directory's own entries, so a symbolic link or a name
     * that differs only by case is not a match.
     */
    public boolean exists(String fileUrl) {
        String canonical = canonicalUrl(fileUrl);
        return canonical != null && list().stream().anyMatch(asset -> asset.fileUrl().equals(canonical));
    }

    /** An existing local asset for the settings binding path, of any supported type. */
    public ModelAsset requireAsset(String fileUrl) {
        String canonical = canonicalUrl(fileUrl);
        if (canonical == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, MODEL_URL_INVALID);
        return list().stream().filter(asset -> asset.fileUrl().equals(canonical)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, MODEL_NOT_FOUND));
    }

    /** Validate an upload's container before publishing its URL. No network I/O. */
    public boolean validStageContainer(Path path) throws IOException {
        return isGlbContainer(path);
    }

    /**
     * The asset a guest was explicitly asked to wear, or a 400 that says exactly
     * why it cannot be: {@link #MODEL_URL_INVALID}, {@link #MODEL_TYPE_UNSUPPORTED},
     * {@link #MODEL_NOT_FOUND} or {@link #MODEL_FILE_INVALID}.
     *
     * Never substitutes another model — the caller asked for this one.
     */
    public ModelAsset requireStageAsset(String fileUrl) {
        String canonical = canonicalUrl(fileUrl);
        if (canonical == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, MODEL_URL_INVALID);
        }
        if (!STAGE_TYPE.equals(typeOf(canonical))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, MODEL_TYPE_UNSUPPORTED);
        }
        ModelAsset asset = list().stream()
                .filter(candidate -> candidate.fileUrl().equals(canonical))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, MODEL_NOT_FOUND));
        if (!asset.stageReady()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, MODEL_FILE_INVALID);
        }
        return asset;
    }

    /**
     * Pick a model for a newly invited guest: a random one nobody is wearing
     * when there is one, otherwise a random one from all of them, so a full
     * tavern does not seat the same face every time. Null when there is no
     * stage-ready model at all — the guest is then invited without one.
     *
     * @param inUse canonical URLs already worn by this user's visible characters
     */
    public ModelAsset pickForGuest(Collection<String> inUse) {
        return pickForGuest(inUse, List.of());
    }

    /**
     * The same choice, made among the shared stage models and {@code alsoWearable}:
     * models from outside this directory that this guest's account may wear, such
     * as the ones it imported into its own library.
     */
    public ModelAsset pickForGuest(Collection<String> inUse, List<ModelAsset> alsoWearable) {
        List<ModelAsset> all = new java.util.ArrayList<>(listForStage());
        if (alsoWearable != null) all.addAll(alsoWearable);
        if (all.isEmpty()) return null;
        List<ModelAsset> free = all.stream().filter(asset -> inUse == null || !inUse.contains(asset.fileUrl())).toList();
        List<ModelAsset> pool = free.isEmpty() ? all : free;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private String typeOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? null : MODEL_TYPES.get(fileName.substring(dot).toLowerCase(Locale.ROOT));
    }

    /**
     * Whether the file opens as a binary glTF 2.0 container, which is what a
     * .vrm is: the "glTF" magic, version 2, a declared length the file really
     * has, and a first chunk of type JSON that fits inside it.
     *
     * This is a container check only. It does not parse the JSON, look for the
     * VRM extension, or validate meshes, textures or bones — a file that passes
     * can still fail to load on the stage.
     */
    private boolean isGlbContainer(Path path) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(GLB_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        // NOFOLLOW: the entry was a regular file a moment ago; do not follow it
        // if it has been swapped for a link since.
        try (SeekableByteChannel channel =
                     Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (header.hasRemaining() && channel.read(header) >= 0) {
                // read() may return fewer bytes than asked for
            }
            if (header.hasRemaining()) return false;
            header.flip();
            if (header.getInt() != GLB_MAGIC || header.getInt() != 2) return false;
            long declaredLength = Integer.toUnsignedLong(header.getInt());
            long jsonLength = Integer.toUnsignedLong(header.getInt());
            return header.getInt() == GLB_JSON_CHUNK && jsonLength > 0 && jsonLength % 4 == 0
                    && GLB_HEADER_BYTES + jsonLength <= declaredLength
                    && declaredLength == channel.size();
        }
    }
}
