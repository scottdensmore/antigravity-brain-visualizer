/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.glaforge.agybrainviz;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * On-disk cache for analysis summaries of an external session source, stored in a tool-owned
 * directory as {@code <id>.summary.json} (and an optional {@code <id>.short_title.txt}). Cache file
 * paths are resolved with a traversal guard since the id originates from a request path variable.
 */
final class SummaryCache {

    private final Path dir;

    SummaryCache(Path dir) {
        this.dir = dir;
    }

    Optional<String> read(String id) throws IOException {
        Path file = file(id, ".summary.json");
        return Files.exists(file) ? Optional.of(Files.readString(file)) : Optional.empty();
    }

    void delete(String id) throws IOException {
        Files.deleteIfExists(file(id, ".summary.json"));
        Files.deleteIfExists(file(id, ".short_title.txt"));
    }

    void write(String id, String summaryJson, String title) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(file(id, ".summary.json"), summaryJson);
        // The short title is best-effort and must not fail the summary cache write.
        if (title != null && !title.isBlank()) {
            try {
                Files.writeString(file(id, ".short_title.txt"), title.trim());
            } catch (IOException ignore) {}
        }
    }

    private Path file(String id, String suffix) {
        Path base = dir.normalize();
        Path file = base.resolve(id + suffix).normalize();
        if (!file.startsWith(base)) {
            throw new IllegalArgumentException("Invalid session id: " + id);
        }
        return file;
    }
}
