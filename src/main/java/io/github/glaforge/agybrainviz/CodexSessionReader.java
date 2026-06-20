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

import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads OpenAI Codex CLI sessions from {@code ~/.codex/sessions} and exposes them through the same
 * shape {@link BrainController} uses for Antigravity transcripts, so the existing frontend can render
 * them. Codex rollout files are converted to the timeline-step schema by {@link CodexAdapter}.
 */
@Singleton
public class CodexSessionReader {

    /** The flavor selector value the frontend sends for Codex sessions. */
    public static final String FLAVOR = "codex";

    private Path sessionsDir() {
        return Paths.get(System.getProperty("user.home"), ".codex", "sessions");
    }

    /**
     * @return one entry per Codex session ({@code id}, {@code summary}, {@code updatedAt}), newest
     *     first. Empty when no Codex sessions exist.
     */
    public List<Map<String, String>> listConversations() {
        Path root = sessionsDir();
        if (!Files.isDirectory(root)) return List.of();

        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                .filter(Files::isRegularFile)
                .map(this::describe)
                .filter(info -> info != null)
                .sorted((a, b) ->
                    Long.compare(
                        Long.parseLong(b.get("updatedAt")),
                        Long.parseLong(a.get("updatedAt"))
                    )
                )
                .toList();
        } catch (IOException e) {
            e.printStackTrace();
            return List.of();
        }
    }

    // Cap how many lines we scan per file when deriving a title for the list view. The clean user
    // prompt is always near the top, so this avoids loading entire (possibly multi-MB) rollouts into
    // memory just to populate the sidebar.
    private static final int SUMMARY_SCAN_LINES = 256;

    private Map<String, String> describe(Path file) {
        try {
            String id = stripExtension(file.getFileName().toString());
            List<String> head;
            try (Stream<String> lines = Files.lines(file)) {
                head = lines.limit(SUMMARY_SCAN_LINES).toList();
            }
            String summary = CodexAdapter
                .deriveSummary(head)
                .orElse("Codex session " + id.substring(0, Math.min(8, id.length())));
            long modified = Files.getLastModifiedTime(file).toMillis();

            Map<String, String> info = new HashMap<>();
            info.put("id", id);
            info.put("summary", summary);
            info.put("updatedAt", String.valueOf(modified));
            return info;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * @param id the session id (the rollout filename without its {@code .jsonl} extension)
     * @return the session's timeline as a JSON array string, or {@code "[]"} if not found
     */
    public String transcriptJson(String id) throws IOException {
        Path file = findById(id);
        if (file == null) return "[]";
        return CodexAdapter.toTranscriptJson(Files.readAllLines(file));
    }

    /**
     * @return whether a Codex session with this id exists.
     */
    public boolean sessionExists(String id) throws IOException {
        return findById(id) != null;
    }

    /**
     * @return the condensed analysis input for a session (one list of lines per sequence), or empty
     *     when the session is not found.
     */
    public List<List<String>> analysisSequences(String id) throws IOException {
        Path file = findById(id);
        if (file == null) return List.of();
        return CodexAdapter.toAnalysisSequences(Files.readAllLines(file));
    }

    // Analysis summaries are cached in a tool-owned hidden directory under ~/.codex/sessions so we
    // never write alongside (or into) the files Codex itself manages.
    private Path cacheDir() {
        return sessionsDir().resolve(".agybrainviz");
    }

    // Resolve a cache file from an id, guarding against traversal even if a caller skips the
    // sessionExists() gate (the id originates from a URL path variable).
    private Path cacheFile(String id, String suffix) {
        Path dir = cacheDir().normalize();
        Path file = dir.resolve(id + suffix).normalize();
        if (!file.startsWith(dir)) {
            throw new IllegalArgumentException("Invalid session id: " + id);
        }
        return file;
    }

    /**
     * @return the cached summary JSON for a session, if present.
     */
    public Optional<String> cachedSummary(String id) throws IOException {
        Path file = cacheFile(id, ".summary.json");
        return Files.exists(file) ? Optional.of(Files.readString(file)) : Optional.empty();
    }

    /** Deletes any cached summary and title for a session (used on forced recompute). */
    public void deleteCache(String id) throws IOException {
        Files.deleteIfExists(cacheFile(id, ".summary.json"));
        Files.deleteIfExists(cacheFile(id, ".short_title.txt"));
    }

    /** Writes the computed summary JSON (and optional short title) to the cache. */
    public void writeCache(String id, String summaryJson, String title) throws IOException {
        Files.createDirectories(cacheDir());
        Files.writeString(cacheFile(id, ".summary.json"), summaryJson);
        // The short title is best-effort and must not fail the summary cache write.
        if (title != null && !title.isBlank()) {
            try {
                Files.writeString(cacheFile(id, ".short_title.txt"), title.trim());
            } catch (IOException ignore) {}
        }
    }

    // Locate a session by matching its id against actual filenames (never build a path from the id,
    // which would allow traversal).
    private Path findById(String id) throws IOException {
        Path root = sessionsDir();
        if (!Files.isDirectory(root)) return null;
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(p -> stripExtension(p.getFileName().toString()).equals(id))
                .findFirst()
                .orElse(null);
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
