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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests the eval harness over a fake source: only cached analyses are scored, and aggregated. */
class EvalServiceTest {

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

    private EvalService eval(FakeSource fake) {
        return new EvalService(
            new SessionCollector(List.of(fake)),
            new AiConfig("gemini", "k", "gemini-3.5-flash", null, null)
        );
    }

    @Test
    void scoresOnlyCachedAnalysesAndAggregates() throws IOException {
        FakeSource fake = new FakeSource();
        fake.add("s-good", "[]", GOOD_SUMMARY);
        fake.add("s-poor", "[]", POOR_SUMMARY);
        fake.add("s-none", "[]", null); // no cached analysis => not evaluated

        EvalReport r = eval(fake).forFlavor("fake");

        assertEquals("fake", r.flavor());
        assertEquals(3, r.sessionCount());
        assertEquals(2, r.evaluatedSessions());
        assertEquals("gemini · gemini-3.5-flash", r.modelLabel());
        // Average of a perfect (100) and a poor case is between them.
        assertTrue(r.avgScore() > 0 && r.avgScore() < 100);
        // The poorest analysis is surfaced first in worst cases.
        assertEquals("s-poor", r.worstCases().get(0).sessionId());
        // Pass-rate for schema-complete: only the good analysis passes it (1 of 2).
        assertTrue(
            r
                .checkPassRates()
                .stream()
                .anyMatch(n -> n.name().equals("schema-complete") && n.count() == 1)
        );
        assertEquals(EvalScorer.checkNames().size(), r.checkPassRates().size());
    }

    @Test
    void reportsZeroWhenNothingHasBeenAnalyzed() throws IOException {
        FakeSource fake = new FakeSource();
        fake.add("s1", "[]", null);

        EvalReport r = eval(fake).forFlavor("fake");

        assertEquals(1, r.sessionCount());
        assertEquals(0, r.evaluatedSessions());
        assertEquals(0.0, r.avgScore());
        assertTrue(r.worstCases().isEmpty());
    }
}
