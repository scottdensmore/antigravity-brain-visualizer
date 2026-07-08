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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/** Tests the file-backed eval run history store over a temporary {@code user.home}. */
@ResourceLock("user.home")
class EvalRunStoreTest {

    private final EvalRunStore store = new EvalRunStore();
    private String originalHome;
    private Path tempHome;

    @BeforeEach
    void setUp() throws IOException {
        originalHome = System.getProperty("user.home");
        tempHome = Files.createTempDirectory("agy-runs-test-home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void tearDown() {
        if (originalHome != null) System.setProperty("user.home", originalHome);
    }

    private EvalReport report(String flavor, double avgScore, JudgeSummary judge) {
        return new EvalReport(
            flavor,
            10,
            10,
            5,
            avgScore,
            "gemini · gemini-3.5-flash",
            List.of(new NameCount("schema-complete", 4)),
            List.of(),
            judge
        );
    }

    @Test
    void savesAndListsNewestFirst() throws IOException {
        store.save(report("codex", 70.0, JudgeSummary.notRun("n")));
        store.save(report("codex", 80.0, JudgeSummary.notRun("n")));

        List<EvalRunSnapshot> runs = store.list("codex");
        assertEquals(2, runs.size());
        assertEquals(80.0, runs.get(0).avgScore()); // newest first
        assertEquals(70.0, runs.get(1).avgScore());
        assertFalse(runs.get(0).judged());
        assertEquals("codex", runs.get(0).flavor());
    }

    @Test
    void filtersByFlavor() throws IOException {
        store.save(report("codex", 70.0, JudgeSummary.notRun("n")));
        store.save(report("claude-code", 90.0, JudgeSummary.notRun("n")));

        List<EvalRunSnapshot> codex = store.list("codex");
        assertEquals(1, codex.size());
        assertEquals("codex", codex.get(0).flavor());
        assertEquals(90.0, store.list("claude-code").get(0).avgScore());
    }

    @Test
    void capturesJudgeAveragesWhenJudged() throws IOException {
        store.save(report("codex", 75.0, new JudgeSummary(true, "", 3, 4.5, 4.0, 3.5, List.of())));

        EvalRunSnapshot run = store.list("codex").get(0);
        assertTrue(run.judged());
        assertEquals(3, run.judgedSessions());
        assertEquals(4.5, run.avgFaithfulness());
        assertEquals(3.5, run.avgClarity());
    }

    @Test
    void capsHistoryToMaxKeepingNewest() throws IOException {
        int n = EvalRunStore.MAX_RUNS + 10;
        for (int i = 0; i < n; i++) {
            store.save(report("codex", i, JudgeSummary.notRun("n")));
        }

        List<EvalRunSnapshot> runs = store.list("codex");
        assertEquals(EvalRunStore.MAX_RUNS, runs.size());
        // The most recent save (avgScore = n-1) is retained and listed first.
        assertEquals((double) (n - 1), runs.get(0).avgScore());
    }

    @Test
    void listIsEmptyWhenNothingSaved() throws IOException {
        assertTrue(store.list("codex").isEmpty());
    }

    @Test
    void defaultsBlankFlavorAndModelSoTheSnapshotStaysListable() throws IOException {
        // A malformed report (no flavor/model) must still round-trip to a listable snapshot.
        EvalRunSnapshot saved = store.save(
            new EvalReport(null, 0, 0, 0, 0.0, null, null, null, null)
        );
        assertEquals("unknown", saved.flavor());
        assertEquals("unknown", saved.modelLabel());
        assertEquals(1, store.list("unknown").size());
    }

    @Test
    void deletesBySavedAtAndLeavesOthers() throws IOException {
        EvalRunSnapshot a = store.save(report("codex", 70.0, JudgeSummary.notRun("n")));
        EvalRunSnapshot b = store.save(report("codex", 80.0, JudgeSummary.notRun("n")));

        assertEquals(1, store.delete(a.savedAt()));
        List<EvalRunSnapshot> remaining = store.list("codex");
        assertEquals(1, remaining.size());
        assertEquals(b.savedAt(), remaining.get(0).savedAt());

        // Deleting an unknown id removes nothing.
        assertEquals(0, store.delete("2000-01-01T00:00:00Z"));
        assertEquals(0, store.delete(null));
        assertEquals(1, store.list("codex").size());
    }

    @Test
    void stampsAParseableServerSideSavedAt() throws IOException {
        // The client cannot dictate identity: savedAt is server-stamped and a valid instant.
        EvalRunSnapshot saved = store.save(report("codex", 50.0, JudgeSummary.notRun("n")));
        assertFalse(saved.savedAt() == null || saved.savedAt().isBlank());
        java.time.Instant.parse(saved.savedAt()); // throws if not a valid ISO-8601 instant
    }
}
