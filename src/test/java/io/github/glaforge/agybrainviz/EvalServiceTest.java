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
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests the eval harness: deterministic scoring plus the opt-in LLM-judge layer and its fallbacks. */
class EvalServiceTest extends PostgresTest {

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);

    @AfterAll
    static void tearDown() {
        EXECUTOR.shutdownNow();
    }

    @BeforeEach
    void reset() throws SQLException {
        resetStore();
    }

    private static final String GOOD_SUMMARY =
        "{\"shortTitle\":\"Fixed build\",\"summary\":\"Updated the JDK and the build passed.\"," +
        "\"flow\":[\"Read config\"],\"recommendations\":[\"Pin the JDK\"]," +
        "\"issues\":[{\"error\":\"Build failed\",\"circumvention\":\"Used JDK 25\"}]}";
    private static final String POOR_SUMMARY = "{\"summary\":\"\"}";

    private static final AnalysisJudgeService FORBIDDEN_JUDGE = (digest, analysis, lens) -> {
        throw new AssertionError("judge must not be called");
    };

    private static AiConfig configured() {
        return new AiConfig("gemini", "k", "gemini-3.5-flash", null, null);
    }

    private static AiConfig notConfigured() {
        return new AiConfig("gemini", "", null, null, null);
    }

    private EvalService eval(AiConfig cfg, AnalysisJudgeService judge) {
        return new EvalService(
            new SessionCollector(new SessionRepository(dataSource())),
            cfg,
            judge,
            EXECUTOR
        );
    }

    @Test
    void scoresOnlyCachedAnalysesAndAggregates() throws IOException {
        seedSession("fake", "s-good", "[]", GOOD_SUMMARY, 1L);
        seedSession("fake", "s-poor", "[]", POOR_SUMMARY, 2L);
        seedSession("fake", "s-none", "[]", null, 3L); // no cached analysis => not evaluated

        EvalReport r = eval(configured(), FORBIDDEN_JUDGE).forFlavor("fake", false);

        assertEquals("fake", r.flavor());
        assertEquals(3, r.sessionCount());
        assertEquals(2, r.evaluatedSessions());
        assertEquals("gemini · gemini-3.5-flash", r.modelLabel());
        assertTrue(r.avgScore() > 0 && r.avgScore() < 100);
        assertEquals("s-poor", r.worstCases().get(0).sessionId());
        assertTrue(
            r
                .checkPassRates()
                .stream()
                .anyMatch(n -> n.name().equals("schema-complete") && n.count() == 1)
        );
        assertFalse(r.judge().ran());
        assertTrue(r.judge().note().contains("Run the LLM judge"));
    }

    @Test
    void reportsZeroWhenNothingHasBeenAnalyzed() throws IOException {
        seedSession("fake", "s1", "[]", null, 1L);

        EvalReport r = eval(configured(), FORBIDDEN_JUDGE).forFlavor("fake", true);

        assertEquals(1, r.sessionCount());
        assertEquals(0, r.evaluatedSessions());
        assertEquals(0.0, r.avgScore());
        assertTrue(r.worstCases().isEmpty());
        assertFalse(r.judge().ran());
        assertTrue(r.judge().note().contains("No analyzed sessions"));
    }

    @Test
    void judgeRatesSampleAndClampsOutOfRangeScores() throws IOException {
        seedSession("fake", "s-good", "[]", GOOD_SUMMARY, 1L);
        seedSession("fake", "s-poor", "[]", POOR_SUMMARY, 2L);
        // The model returns out-of-range values that must be clamped into [1, 5] before averaging.
        AnalysisJudgeService judge = (digest, analysis, lens) ->
            new JudgeScore(9, 0, 3, "looks fine");

        EvalReport r = eval(configured(), judge).forFlavor("fake", true);

        assertTrue(r.judge().ran());
        assertEquals(2, r.judge().judgedSessions());
        assertEquals(2, r.judge().cases().size());
        assertEquals(5.0, r.judge().avgFaithfulness()); // 9 -> 5
        assertEquals(1.0, r.judge().avgActionability()); // 0 -> 1
        assertEquals(3.0, r.judge().avgClarity());
        assertEquals(5.0, r.judge().cases().get(0).faithfulness());
        assertEquals(EvalService.JUDGE_LENSES.size(), r.judge().cases().get(0).samples());
    }

    @Test
    void judgePanelAveragesDiverseLensVerdicts() throws IOException {
        seedSession("fake", "s1", "[]", GOOD_SUMMARY, 1L);
        // Each lens returns a different faithfulness (strict 3 / balanced 4 / pragmatic 5); the panel
        // average is 4.0, so no single framing dominates. Actionability/clarity are constant.
        AnalysisJudgeService judge = (digest, analysis, lens) -> {
            int faithfulness = lens.contains("strict") ? 3 : lens.contains("pragmatic") ? 5 : 4;
            return new JudgeScore(faithfulness, 2, 5, "c");
        };

        EvalReport r = eval(configured(), judge).forFlavor("fake", true);

        JudgedCase c = r.judge().cases().get(0);
        assertEquals(4.0, c.faithfulness());
        assertEquals(2.0, c.actionability());
        assertEquals(5.0, c.clarity());
        assertEquals(3, c.samples());
        // Per-lens overalls: (3+2+5)/3=3.3, (4+2+5)/3=3.7, (5+2+5)/3=4.0 => panel spread 3.3–4.0.
        assertEquals(3.3, c.panelMin());
        assertEquals(4.0, c.panelMax());
    }

    @Test
    void judgeEnsemblesSurvivingLensesWhenSomeFail() throws IOException {
        seedSession("fake", "s1", "[]", GOOD_SUMMARY, 1L);
        // The strict panelist errors; the other two still return verdicts, so the case survives.
        AnalysisJudgeService judge = (digest, analysis, lens) -> {
            if (lens.contains("strict")) throw new RuntimeException("boom");
            return new JudgeScore(4, 4, 4, "c");
        };

        EvalReport r = eval(configured(), judge).forFlavor("fake", true);

        assertTrue(r.judge().ran());
        JudgedCase c = r.judge().cases().get(0);
        assertEquals(2, c.samples()); // only the two surviving lenses contributed
        assertEquals(4.0, c.faithfulness());
    }

    @Test
    void judgeSkippedWhenAiNotConfigured() throws IOException {
        seedSession("fake", "s-good", "[]", GOOD_SUMMARY, 1L);

        EvalReport r = eval(notConfigured(), FORBIDDEN_JUDGE).forFlavor("fake", true);

        assertFalse(r.judge().ran());
        assertTrue(r.judge().note().contains("Configure an AI provider"));
        assertEquals(1, r.evaluatedSessions());
    }

    @Test
    void judgeDegradesGracefullyWhenModelFails() throws IOException {
        seedSession("fake", "s-good", "[]", GOOD_SUMMARY, 1L);
        AnalysisJudgeService judge = (digest, analysis, lens) -> {
            throw new RuntimeException("model timeout");
        };

        EvalReport r = eval(configured(), judge).forFlavor("fake", true);

        assertFalse(r.judge().ran());
        assertTrue(r.judge().note().contains("unavailable"));
        assertEquals(1, r.evaluatedSessions());
    }

    @Test
    void judgeSampleIsCappedAtMax() throws IOException {
        int analyzed = EvalService.JUDGE_MAX_SESSIONS + 5;
        for (int i = 0; i < analyzed; i++) {
            seedSession("fake", "s" + i, "[]", GOOD_SUMMARY, i);
        }
        AnalysisJudgeService judge = (digest, analysis, lens) -> new JudgeScore(4, 4, 4, "ok");

        EvalReport r = eval(configured(), judge).forFlavor("fake", true);

        assertEquals(analyzed, r.evaluatedSessions());
        assertEquals(EvalService.JUDGE_MAX_SESSIONS, r.judge().judgedSessions());
    }
}
