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

/** Tests the {@link SessionSource}-backed path (Codex/Claude Code) of {@link InsightsService}. */
class InsightsServiceTest {

    /** Minimal in-memory SessionSource for the "fake" flavor. */
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

    @Test
    void aggregatesSessionsFromASource() throws IOException {
        FakeSource fake = new FakeSource();
        fake.add(
            "s1",
            "[{\"created_at\":\"2026-06-19T10:00:00Z\",\"tool_calls\":[{\"name\":\"Bash\"}]}," +
            "{\"type\":\"ERROR_MESSAGE\",\"status\":\"ERROR\",\"error\":\"boom\",\"created_at\":\"2026-06-19T10:00:05Z\"}]",
            "{\"recommendations\":[\"Use a linter\"],\"issues\":[{\"error\":\"Flaky test\"}]}"
        );
        fake.add(
            "s2",
            "[{\"created_at\":\"2026-06-19T11:00:00Z\",\"tool_calls\":[{\"name\":\"Read\"}]}]",
            null
        );

        InsightsReport r = new InsightsService(List.of(fake)).forFlavor("fake");

        assertEquals("fake", r.flavor());
        assertEquals(2, r.sessionCount());
        assertEquals(2, r.sampledSessions());
        assertEquals(1, r.analyzedSessions());
        assertEquals(2, r.toolCallTotal());
        assertEquals(1, r.sessionsWithErrors());
        assertEquals(1, r.cleanSessions());
        assertTrue(r.topTools().stream().anyMatch(n -> n.name().equals("Bash")));
        assertTrue(r.topRecommendations().stream().anyMatch(n -> n.name().equals("Use a linter")));
        assertTrue(r.topIssues().stream().anyMatch(n -> n.name().equals("Flaky test")));
    }

    @Test
    void capsScanAtMaxSessionsButReportsTrueTotal() throws IOException {
        FakeSource fake = new FakeSource();
        int total = InsightsService.MAX_SESSIONS + 50;
        for (int i = 0; i < total; i++) {
            fake.add("s" + i, "[]", null);
        }

        InsightsReport r = new InsightsService(List.of(fake)).forFlavor("fake");
        assertEquals(total, r.sessionCount());
        assertEquals(InsightsService.MAX_SESSIONS, r.sampledSessions());
    }
}
