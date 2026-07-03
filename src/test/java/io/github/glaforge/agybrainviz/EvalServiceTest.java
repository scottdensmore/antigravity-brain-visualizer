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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** Tests the eval harness: deterministic scoring plus the opt-in LLM-judge layer and its fallbacks. */
class EvalServiceTest {

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);

    @AfterAll
    static void tearDown() {
        EXECUTOR.shutdownNow();
    }

    private static class FakeSource implements SessionSource {

        private final Map<String, String> transcripts = new LinkedHashMap<>();
        private final Map<String, String> summaries = new HashMap<>();

        void add(String id, String transcriptJson, String summaryJson) {
            transcripts.put(id, transcriptJson);
            if (summaryJson != null) summaries.put(id, summaryJson);
        }

        @Override
        public boolean handles(String flavor) {
            return "fake".equals(flavor);
        }

        @Override
        public List<Map<String, String>> listConversations() {
            List<Map<String, String>> list = new ArrayList<>();
            for (String id : transcripts.keySet()) {
                Map<String, String> info = new HashMap<>();
                info.put("id", id);
                list.add(info);
            }
            return list;
        }

        @Override
        public String transcriptJson(String id) {
            return transcripts.getOrDefault(id, "[]");
        }

        @Override
        public Optional<String> cachedSummary(String id) {
            return Optional.ofNullable(summaries.get(id));
        }

        @Override
        public boolean sessionExists(String id) {
            return transcripts.containsKey(id);
        }

        @Override
        public List<List<String>> analysisSequences(String id) {
            return List.of();
        }

        @Override
        public void deleteCache(String id) {}

        @Override
        public void writeCache(String id, String summaryJson, String title) {}
    }

    private static final String GOOD_SUMMARY =
        "{\"shortTitle\":\"Fixed build\",\"summary\":\"Updated the JDK and the build passed.\"," +
        "\"flow\":[\"Read config\"],\"recommendations\":[\"Pin the JDK\"]," +
        "\"issues\":[{\"error\":\"Build failed\",\"circumvention\":\"Used JDK 25\"}]}";
    private static final String POOR_SUMMARY = "{\"summary\":\"\"}";

    private static final AnalysisJudgeService FORBIDDEN_JUDGE = (digest, analysis) -> {
        throw new AssertionError("judge must not be called");
    };

    private static AiConfig configured() {
        return new AiConfig("gemini", "k", "gemini-3.5-flash", null, null);
    }

    private static AiConfig notConfigured() {
        return new AiConfig("gemini", "", null, null, null);
    }

    private EvalService eval(FakeSource fake, AiConfig cfg, AnalysisJudgeService judge) {
        return new EvalService(new SessionCollector(List.of(fake)), cfg, judge, EXECUTOR);
    }

    @Test
    void scoresOnlyCachedAnalysesAndAggregates() throws IOException {
        FakeSource fake = new FakeSource();
        fake.add("s-good", "[]", GOOD_SUMMARY);
        fake.add("s-poor", "[]", POOR_SUMMARY);
        fake.add("s-none", "[]", null); // no cached analysis => not evaluated

        EvalReport r = eval(fake, configured(), FORBIDDEN_JUDGE).forFlavor("fake", false);

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
        // Judge was not requested: the deterministic report stands on its own.
        assertFalse(r.judge().ran());
        assertTrue(r.judge().note().contains("Run the LLM judge"));
    }

    @Test
    void reportsZeroWhenNothingHasBeenAnalyzed() throws IOException {
        FakeSource fake = new FakeSource();
        fake.add("s1", "[]", null);

        EvalReport r = eval(fake, configured(), FORBIDDEN_JUDGE).forFlavor("fake", true);

        assertEquals(1, r.sessionCount());
        assertEquals(0, r.evaluatedSessions());
        assertEquals(0.0, r.avgScore());
        assertTrue(r.worstCases().isEmpty());
        // Judge requested but nothing to judge.
        assertFalse(r.judge().ran());
        assertTrue(r.judge().note().contains("No analyzed sessions"));
    }

    @Test
    void judgeRatesSampleAndClampsOutOfRangeScores() throws IOException {
        FakeSource fake = new FakeSource();
        fake.add("s-good", "[]", GOOD_SUMMARY);
        fake.add("s-poor", "[]", POOR_SUMMARY);
        // The model returns out-of-range values that must be clamped into [1, 5].
        AnalysisJudgeService judge = (digest, analysis) -> new JudgeScore(9, 0, 3, "looks fine");

        EvalReport r = eval(fake, configured(), judge).forFlavor("fake", true);

        assertTrue(r.judge().ran());
        assertEquals(2, r.judge().judgedSessions());
        assertEquals(2, r.judge().cases().size());
        assertEquals(5.0, r.judge().avgFaithfulness()); // 9 -> 5
        assertEquals(1.0, r.judge().avgActionability()); // 0 -> 1
        assertEquals(3.0, r.judge().avgClarity());
        assertEquals(5, r.judge().cases().get(0).score().faithfulness());
    }

    @Test
    void judgeSkippedWhenAiNotConfigured() throws IOException {
        FakeSource fake = new FakeSource();
        fake.add("s-good", "[]", GOOD_SUMMARY);

        EvalReport r = eval(fake, notConfigured(), FORBIDDEN_JUDGE).forFlavor("fake", true);

        assertFalse(r.judge().ran());
        assertTrue(r.judge().note().contains("Configure an AI provider"));
        // The deterministic pass still produced a score.
        assertEquals(1, r.evaluatedSessions());
    }

    @Test
    void judgeDegradesGracefullyWhenModelFails() throws IOException {
        FakeSource fake = new FakeSource();
        fake.add("s-good", "[]", GOOD_SUMMARY);
        AnalysisJudgeService judge = (digest, analysis) -> {
            throw new RuntimeException("model timeout");
        };

        EvalReport r = eval(fake, configured(), judge).forFlavor("fake", true);

        assertFalse(r.judge().ran());
        assertTrue(r.judge().note().contains("unavailable"));
        // Deterministic results are preserved despite the judge failure.
        assertEquals(1, r.evaluatedSessions());
    }

    @Test
    void judgeSampleIsCappedAtMax() throws IOException {
        FakeSource fake = new FakeSource();
        int analyzed = EvalService.JUDGE_MAX_SESSIONS + 5;
        for (int i = 0; i < analyzed; i++) {
            fake.add("s" + i, "[]", GOOD_SUMMARY);
        }
        AnalysisJudgeService judge = (digest, analysis) -> new JudgeScore(4, 4, 4, "ok");

        EvalReport r = eval(fake, configured(), judge).forFlavor("fake", true);

        assertEquals(analyzed, r.evaluatedSessions());
        assertEquals(EvalService.JUDGE_MAX_SESSIONS, r.judge().judgedSessions());
    }
}
