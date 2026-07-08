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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persists {@link EvalRunSnapshot}s to a JSON-lines file under the user's home so eval quality can be
 * tracked and compared across runs. Append-on-save, newest-first on read, and capped so the file
 * stays small. Single-user local tool, so a coarse lock is enough for correctness.
 */
@Singleton
public class EvalRunStore {

    /** Keep the history bounded; on overflow the oldest runs are dropped. */
    static final int MAX_RUNS = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Object lock = new Object();

    private Path historyFile() {
        return Paths.get(System.getProperty("user.home"), ".agybrainviz", "eval-runs.jsonl");
    }

    /** Records a snapshot of the given report (server-stamped) and returns it. */
    public EvalRunSnapshot save(EvalReport report) throws IOException {
        EvalRunSnapshot snapshot = snapshotOf(report, Instant.now().toString());
        synchronized (lock) {
            List<String> lines = new ArrayList<>(readLines());
            lines.add(MAPPER.writeValueAsString(snapshot));
            if (lines.size() > MAX_RUNS) {
                lines = new ArrayList<>(lines.subList(lines.size() - MAX_RUNS, lines.size()));
            }
            Path file = historyFile();
            Files.createDirectories(file.getParent());
            Files.write(file, lines);
        }
        return snapshot;
    }

    /** All saved runs for a flavor, newest first. */
    public List<EvalRunSnapshot> list(String flavor) throws IOException {
        List<EvalRunSnapshot> runs = new ArrayList<>();
        synchronized (lock) {
            for (String line : readLines()) {
                if (line == null || line.isBlank()) continue;
                try {
                    EvalRunSnapshot run = MAPPER.readValue(line, EvalRunSnapshot.class);
                    if (flavor == null || flavor.equals(run.flavor())) runs.add(run);
                } catch (Exception e) {
                    // skip a malformed line rather than failing the whole history
                }
            }
        }
        // File order is chronological (append-on-save); reverse for newest-first.
        Collections.reverse(runs);
        return runs;
    }

    /** Removes the saved run(s) with the given {@code savedAt} id; returns how many were removed. */
    public int delete(String savedAt) throws IOException {
        if (savedAt == null || savedAt.isBlank()) return 0;
        synchronized (lock) {
            List<String> lines = readLines();
            List<String> kept = new ArrayList<>();
            int removed = 0;
            for (String line : lines) {
                if (matchesSavedAt(line, savedAt)) {
                    removed++;
                } else {
                    kept.add(line);
                }
            }
            if (removed > 0) {
                Path file = historyFile();
                Files.createDirectories(file.getParent());
                Files.write(file, kept);
            }
            return removed;
        }
    }

    // A malformed line can't match (and is preserved), so a bad line never blocks deletion.
    private static boolean matchesSavedAt(String line, String savedAt) {
        if (line == null || line.isBlank()) return false;
        try {
            return savedAt.equals(MAPPER.readValue(line, EvalRunSnapshot.class).savedAt());
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> readLines() throws IOException {
        Path file = historyFile();
        return Files.exists(file) ? Files.readAllLines(file) : List.of();
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static EvalRunSnapshot snapshotOf(EvalReport report, String savedAt) {
        JudgeSummary judge = report.judge();
        boolean judged = judge != null && judge.ran();
        // Default the identity/label fields so a malformed body can't persist an unlistable snapshot.
        return new EvalRunSnapshot(
            savedAt,
            blankTo(report.flavor(), "unknown"),
            blankTo(report.modelLabel(), "unknown"),
            report.sessionCount(),
            report.evaluatedSessions(),
            report.avgScore(),
            report.checkPassRates() == null ? List.of() : report.checkPassRates(),
            judged,
            judged ? judge.judgedSessions() : 0,
            judged ? judge.avgFaithfulness() : 0.0,
            judged ? judge.avgActionability() : 0.0,
            judged ? judge.avgClarity() : 0.0
        );
    }
}
