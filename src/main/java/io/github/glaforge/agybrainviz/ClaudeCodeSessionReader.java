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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads Claude Code sessions from {@code ~/.claude/projects/<dir>/<uuid>.jsonl} and exposes them
 * through {@link SessionSource}, so the existing frontend and analysis pipeline can render and
 * summarize them. Rollout lines are converted by {@link ClaudeCodeAdapter}.
 */
@Singleton
public class ClaudeCodeSessionReader implements SessionSource {

    /** The flavor selector value the frontend sends for Claude Code sessions. */
    public static final String FLAVOR = "claude-code";

    private static final int SUMMARY_SCAN_LINES = 256;

    @Override
    public boolean handles(String flavor) {
        return FLAVOR.equals(flavor);
    }

    private Path projectsDir() {
        return Paths.get(System.getProperty("user.home"), ".claude", "projects");
    }

    @Override
    public List<Map<String, String>> listConversations() {
        Path root = projectsDir();
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

    private Map<String, String> describe(Path file) {
        try {
            String id = stripExtension(file.getFileName().toString());
            List<String> head;
            try (Stream<String> lines = Files.lines(file)) {
                head = lines.limit(SUMMARY_SCAN_LINES).toList();
            }
            String summary = ClaudeCodeAdapter
                .deriveSummary(head)
                .orElse("Claude Code session " + id.substring(0, Math.min(8, id.length())));
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

    @Override
    public String transcriptJson(String id) throws IOException {
        Path file = findById(id);
        if (file == null) return "[]";
        return ClaudeCodeAdapter.toTranscriptJson(Files.readAllLines(file));
    }

    @Override
    public boolean sessionExists(String id) throws IOException {
        return findById(id) != null;
    }

    @Override
    public List<List<String>> analysisSequences(String id) throws IOException {
        Path file = findById(id);
        if (file == null) return List.of();
        return ClaudeCodeAdapter.toAnalysisSequences(Files.readAllLines(file));
    }

    // Summaries are cached in a tool-owned hidden directory under ~/.claude/projects so we never
    // write alongside the files Claude Code itself manages.
    private SummaryCache cache() {
        return new SummaryCache(projectsDir().resolve(".agybrainviz"));
    }

    @Override
    public Optional<String> cachedSummary(String id) throws IOException {
        return cache().read(id);
    }

    @Override
    public void deleteCache(String id) throws IOException {
        cache().delete(id);
    }

    @Override
    public void writeCache(String id, String summaryJson, String title) throws IOException {
        cache().write(id, summaryJson, title);
    }

    // Locate a session by matching its id against actual filenames (never build a path from the id,
    // which would allow traversal).
    private Path findById(String id) throws IOException {
        Path root = projectsDir();
        if (!Files.isDirectory(root)) return null;
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
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
