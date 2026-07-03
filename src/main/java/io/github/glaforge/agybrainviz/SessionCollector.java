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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Gathers a source's sessions — each session's normalized timeline steps plus any cached AI analysis
 * — for the cross-session features ({@link InsightsService}, {@code MinerService}). Antigravity is
 * read directly from {@code ~/.gemini/<flavor>/brain}; other sources go through their
 * {@link SessionSource}.
 *
 * <p>The scan is capped at the most recent {@link #MAX_SESSIONS} sessions so callers stay responsive
 * even for very large histories; {@link Collected#totalSessionCount()} still reports the true total.
 */
@Singleton
public class SessionCollector {

    static final int MAX_SESSIONS = 150;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<SessionSource> sources;

    @Inject
    public SessionCollector(List<SessionSource> sources) {
        this.sources = sources;
    }

    /** The gathered sessions for a flavor and the true total (which may exceed the sampled size). */
    public record Collected(int totalSessionCount, List<FleetInsights.Session> sessions) {}

    public Collected collect(String flavor) throws IOException {
        Optional<SessionSource> source = sources
            .stream()
            .filter(s -> s.handles(flavor))
            .findFirst();
        return source.isPresent() ? fromSource(source.get()) : fromAntigravity(flavor);
    }

    private Collected fromSource(SessionSource source) throws IOException {
        List<Map<String, String>> conversations = source.listConversations();
        List<FleetInsights.Session> sessions = new ArrayList<>();
        for (Map<String, String> info : conversations) {
            if (sessions.size() >= MAX_SESSIONS) break;
            String id = info.get("id");
            List<JsonNode> steps = parseArray(source.transcriptJson(id));
            JsonNode summary = source.cachedSummary(id).map(this::parseJson).orElse(null);
            sessions.add(new FleetInsights.Session(id, steps, summary));
        }
        return new Collected(conversations.size(), sessions);
    }

    private Collected fromAntigravity(String flavor) throws IOException {
        String dirName = (flavor == null || flavor.isEmpty()) ? "antigravity-cli" : flavor;
        Path brain = Paths.get(System.getProperty("user.home"), ".gemini", dirName, "brain");
        if (!Files.isDirectory(brain)) {
            return new Collected(0, List.of());
        }

        List<Path> dirs;
        try (Stream<Path> paths = Files.list(brain)) {
            dirs =
                paths
                    .filter(Files::isDirectory)
                    .filter(d -> transcriptOf(d) != null)
                    .sorted(Comparator.comparingLong(this::lastModified).reversed())
                    .toList();
        }

        List<FleetInsights.Session> sessions = new ArrayList<>();
        for (Path dir : dirs) {
            if (sessions.size() >= MAX_SESSIONS) break;
            Path transcript = readableTranscript(dir);
            if (transcript == null) continue;
            try {
                List<JsonNode> steps = parseLines(Files.readAllLines(transcript));
                Path logs = dir.resolve(".system_generated").resolve("logs");
                JsonNode summary = readJson(logs.resolve("summary.json"));
                sessions.add(
                    new FleetInsights.Session(dir.getFileName().toString(), steps, summary)
                );
            } catch (IOException e) {
                // A session may be deleted/rotated mid-scan; skip it rather than failing the report.
            }
        }
        return new Collected(dirs.size(), sessions);
    }

    // A session is listed (and sorted) by its transcript.jsonl, mirroring BrainController.list.
    private Path transcriptOf(Path sessionDir) {
        return nonEmpty(
            sessionDir.resolve(".system_generated").resolve("logs").resolve("transcript.jsonl")
        );
    }

    // For reading, prefer transcript_full.jsonl when present, mirroring BrainController.getTranscript,
    // so the counts match the rendered timeline.
    private Path readableTranscript(Path sessionDir) {
        Path logs = sessionDir.resolve(".system_generated").resolve("logs");
        Path full = nonEmpty(logs.resolve("transcript_full.jsonl"));
        return full != null ? full : nonEmpty(logs.resolve("transcript.jsonl"));
    }

    private Path nonEmpty(Path file) {
        try {
            return (Files.exists(file) && Files.size(file) > 0) ? file : null;
        } catch (IOException e) {
            return null;
        }
    }

    private long lastModified(Path sessionDir) {
        Path transcript = transcriptOf(sessionDir);
        if (transcript == null) return 0L;
        try {
            return Files.getLastModifiedTime(transcript).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    private List<JsonNode> parseArray(String jsonArray) {
        List<JsonNode> steps = new ArrayList<>();
        try {
            JsonNode arr = MAPPER.readTree(jsonArray);
            if (arr.isArray()) arr.forEach(steps::add);
        } catch (Exception e) {
            // ignore malformed transcript
        }
        return steps;
    }

    private List<JsonNode> parseLines(List<String> lines) {
        List<JsonNode> steps = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            try {
                steps.add(MAPPER.readTree(line));
            } catch (Exception e) {
                // skip malformed line
            }
        }
        return steps;
    }

    private JsonNode parseJson(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode readJson(Path path) {
        try {
            return Files.exists(path) ? MAPPER.readTree(Files.readString(path)) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
