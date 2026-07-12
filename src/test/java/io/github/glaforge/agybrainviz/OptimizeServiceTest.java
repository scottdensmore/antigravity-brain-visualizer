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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests the prompt lab: eval-as-fitness comparison of two instructions, with graceful fallbacks. */
class OptimizeServiceTest extends PostgresTest {

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);

    @AfterAll
    static void tearDown() {
        EXECUTOR.shutdownNow();
    }

    @BeforeEach
    void reset() throws SQLException {
        resetStore();
    }

    // A well-formed analysis (passes all deterministic checks).
    private static AnalysisResponse good() {
        return new AnalysisResponse(
            "Fixed it",
            List.of("did a thing"),
            List.of(),
            List.of(new Issue("Build failed", "used JDK 25")),
            List.of("Pin the JDK"),
            "The agent fixed the build."
        );
    }

    // A degenerate analysis (no title, no flow, no recs) — scores low.
    private static AnalysisResponse poor() {
        return new AnalysisResponse("", List.of(), List.of(), List.of(), List.of(), "");
    }

    private static AiConfig configured() {
        return new AiConfig("gemini", "k", null, null, null);
    }

    private static AiConfig notConfigured() {
        return new AiConfig("gemini", "", null, null, null);
    }

    private void seedSessions(int sessions) {
        for (int i = 0; i < sessions; i++) {
            seedSession("fake", "s" + i, "[{\"type\":\"USER_INPUT\",\"content\":\"go\"}]", null, i);
        }
    }

    private OptimizeService service(VariantAnalyzerService analyzer, AiConfig cfg) {
        return new OptimizeService(
            new SessionCollector(new SessionRepository(dataSource())),
            analyzer,
            cfg,
            EXECUTOR
        );
    }

    @Test
    void scoresBothVariantsSoTheWinnerIsVisible() throws IOException {
        seedSessions(3);
        // Instruction A yields good analyses, B yields poor ones.
        VariantAnalyzerService analyzer = (instruction, transcript) ->
            instruction.contains("BETTER") ? good() : poor();

        OptimizeReport r = service(analyzer, configured())
            .compare("fake", 3, "BETTER prompt", "worse prompt");

        assertEquals(3, r.sampleSize());
        assertEquals(3, r.a().scored());
        assertEquals(3, r.b().scored());
        assertTrue(r.a().avgScore() > r.b().avgScore());
        assertEquals(100.0, r.a().avgScore());
        assertEquals(EvalScorer.checkNames().size(), r.a().checkPassRates().size());
    }

    @Test
    void capsTheSampleSize() throws IOException {
        seedSessions(20);
        VariantAnalyzerService analyzer = (instruction, transcript) -> good();
        OptimizeReport r = service(analyzer, configured()).compare("fake", 99, "a", "b");
        assertEquals(OptimizeService.MAX_SAMPLE, r.sampleSize());
    }

    @Test
    void degradesWhenAiNotConfigured() throws IOException {
        seedSessions(3);
        VariantAnalyzerService analyzer = (instruction, transcript) -> {
            throw new AssertionError("analyzer must not be called when AI is not configured");
        };
        OptimizeReport r = service(analyzer, notConfigured()).compare("fake", 3, "a", "b");
        assertEquals(0, r.sampleSize());
        assertTrue(r.note().contains("Configure an AI provider"));
    }

    @Test
    void reportsWhenThereAreNoSessions() throws IOException {
        VariantAnalyzerService analyzer = (instruction, transcript) -> good();
        OptimizeReport r = service(analyzer, configured()).compare("fake", 3, "a", "b");
        assertEquals(0, r.sampleSize());
        assertTrue(r.note().contains("No sessions"));
    }

    @Test
    void degradesGracefullyWhenAnAnalysisFails() throws IOException {
        seedSessions(2);
        // Variant B always throws; A always succeeds. B simply scores nothing rather than erroring.
        VariantAnalyzerService analyzer = (instruction, transcript) -> {
            if (instruction.contains("boom")) throw new RuntimeException("model down");
            return good();
        };
        OptimizeReport r = service(analyzer, configured()).compare("fake", 2, "ok", "boom");
        assertEquals(2, r.a().scored());
        assertEquals(0, r.b().scored());
        assertEquals(0.0, r.b().avgScore());
    }
}
