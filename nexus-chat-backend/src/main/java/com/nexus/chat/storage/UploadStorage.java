package com.nexus.chat.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Where uploaded files live.
 *
 * Stored URLs look like {@code /uploads/2026/11/22/<id>.jpeg} and outlive any one
 * process, so the directory behind them must not depend on where the server happened
 * to be started. It is read once from {@code nexus.uploads.dir} and made absolute; a
 * relative value (the default, {@code uploads}) is resolved against the working
 * directory of this start-up, which is the behaviour before this setting existed.
 */
@Slf4j
@Component
public class UploadStorage {
    /** Public URL prefix every stored file is served under. */
    public static final String URL_PREFIX = "/uploads/";

    private final Path root;

    public UploadStorage(@Value("${nexus.uploads.dir:uploads}") String directory) {
        this.root = Paths.get(directory == null || directory.isBlank() ? "uploads" : directory.trim())
                .toAbsolutePath().normalize();
        log.info("Uploads are stored in {}", root);
    }

    public Path root() {
        return root;
    }

    /** A path under the uploads directory. Anything that would leave it is refused. */
    public Path resolve(String relative) {
        Path target = root.resolve(relative == null ? "" : relative).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Path leaves the uploads directory");
        return target;
    }

    /** The file a stored {@code /uploads/...} URL points at, or null for any other value. */
    public Path fileOf(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) return null;
        try {
            return resolve(url.substring(URL_PREFIX.length()));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
