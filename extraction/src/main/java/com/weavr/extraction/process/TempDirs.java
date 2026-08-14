package com.weavr.extraction.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared cleanup for the per-call scratch directories the cascade creates —
 * subtitles, downloaded audio, downloaded video, cut frames. Storage cost,
 * legal exposure and a bounded disk all argue the same way: nothing survives
 * the call that created it.
 */
public final class TempDirs {

    private static final Logger log = LoggerFactory.getLogger(TempDirs.class);

    private TempDirs() {
    }

    public static void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort; the OS reclaims temp space regardless.
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean temp directory {}", directory, e);
        }
    }
}
