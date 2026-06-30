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
import org.junit.jupiter.api.Test;

/** Tests the miner's evidence gathering and its graceful degradation around the LLM pass. */
class MinerServiceTest {

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

    private static final String READ_EDIT_BASH =
        "[{\"tool_calls\":[{\"name\":\"Read\"}]}," +
        "{\"tool_calls\":[{\"name\":\"Edit\"}]}," +
        "{\"tool_calls\":[{\"name\":\"Bash\"}]}]";
    private static final String SUMMARY =
        "{\"recommendations\":[\"Add a lint rule\"]," +
        "\"issues\":[{\"error\":\"Build fails on JDK 21\",\"circumvention\":\"Use JDK 25 via mise\"}]}";

    private FakeSource sourceWithEvidence() {
        FakeSource fake = new FakeSource();
        fake.add("s1", READ_EDIT_BASH, SUMMARY);
        fake.add("s2", READ_EDIT_BASH, SUMMARY);
        return fake;
    }

    private static AiConfig configured() {
        return new AiConfig("gemini", "test-key", null, null, null);
    }

    private static AiConfig notConfigured() {
        return new AiConfig("gemini", "", null, null, null);
    }

    private MinerService miner(FakeSource fake, MinerAdvisorService advisor, AiConfig aiConfig) {
        return new MinerService(new SessionCollector(List.of(fake)), advisor, aiConfig);
    }

    @Test
    void minesEvidenceAndProposalsWhenAiConfigured() throws IOException {
        MinerAdvisorService advisor = evidence ->
            new MiningProposal(
                List.of(
                    new SkillProposal(
                        "read-edit-bash",
                        "When editing then verifying",
                        "1. Read 2. Edit 3. Bash"
                    )
                ),
                List.of(new AgentsRule("Use JDK 25 via mise", "The build fails on JDK 21")),
                List.of("A one-shot build-and-test tool")
            );

        MiningReport r = miner(sourceWithEvidence(), advisor, configured()).forFlavor("fake");

        assertTrue(r.aiGenerated());
        assertEquals(2, r.sessionCount());
        assertEquals(2, r.analyzedSessions());
        // Structural evidence.
        assertTrue(r.toolSequences().stream().anyMatch(n -> n.name().equals("Read → Edit → Bash")));
        assertTrue(
            r.failureFixes().stream().anyMatch(f -> f.error().equals("Build fails on JDK 21"))
        );
        assertTrue(r.recommendations().stream().anyMatch(n -> n.name().equals("Add a lint rule")));
        // LLM proposals.
        assertEquals("read-edit-bash", r.skills().get(0).name());
        assertEquals("Use JDK 25 via mise", r.agentsRules().get(0).rule());
        assertFalse(r.toolingGaps().isEmpty());
    }

    @Test
    void returnsEvidenceOnlyWhenAiNotConfigured() throws IOException {
        MinerAdvisorService advisor = evidence -> {
            throw new AssertionError("advisor must not be called when AI is not configured");
        };

        MiningReport r = miner(sourceWithEvidence(), advisor, notConfigured()).forFlavor("fake");

        assertFalse(r.aiGenerated());
        assertTrue(r.note().contains("Configure an AI provider"));
        assertFalse(r.toolSequences().isEmpty());
        assertTrue(r.skills().isEmpty());
        assertTrue(r.agentsRules().isEmpty());
    }

    @Test
    void degradesGracefullyWhenAdvisorFails() throws IOException {
        MinerAdvisorService advisor = evidence -> {
            throw new RuntimeException("model timeout");
        };

        MiningReport r = miner(sourceWithEvidence(), advisor, configured()).forFlavor("fake");

        assertFalse(r.aiGenerated());
        assertTrue(r.note().contains("unavailable"));
        // The expensive structural work is preserved despite the LLM failure.
        assertTrue(r.toolSequences().stream().anyMatch(n -> n.name().equals("Read → Edit → Bash")));
        assertTrue(r.skills().isEmpty());
    }

    @Test
    void reportsWhenThereIsNotEnoughEvidence() throws IOException {
        FakeSource fake = new FakeSource();
        // A single session with no recurring sequence and no cached analysis: nothing to mine.
        fake.add("s1", "[{\"tool_calls\":[{\"name\":\"Read\"}]}]", null);
        MinerAdvisorService advisor = evidence -> {
            throw new AssertionError("advisor must not be called when there is no evidence");
        };

        MiningReport r = miner(fake, advisor, configured()).forFlavor("fake");

        assertFalse(r.aiGenerated());
        assertTrue(r.note().contains("Not enough recurring patterns"));
        assertTrue(r.toolSequences().isEmpty());
    }
}
