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

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests the miner's evidence gathering and its graceful degradation around the LLM pass. */
class MinerServiceTest extends PostgresTest {

    private static final String READ_EDIT_BASH =
        "[{\"tool_calls\":[{\"name\":\"Read\"}]}," +
        "{\"tool_calls\":[{\"name\":\"Edit\"}]}," +
        "{\"tool_calls\":[{\"name\":\"Bash\"}]}]";
    private static final String SUMMARY =
        "{\"recommendations\":[\"Add a lint rule\"]," +
        "\"issues\":[{\"error\":\"Build fails on JDK 21\",\"circumvention\":\"Use JDK 25 via mise\"}]}";

    @BeforeEach
    void reset() throws SQLException {
        resetStore();
    }

    private void seedEvidence() {
        seedSession("fake", "s1", READ_EDIT_BASH, SUMMARY, 1L);
        seedSession("fake", "s2", READ_EDIT_BASH, SUMMARY, 2L);
    }

    private static AiConfig configured() {
        return new AiConfig("gemini", "test-key", null, null, null);
    }

    private static AiConfig notConfigured() {
        return new AiConfig("gemini", "", null, null, null);
    }

    private MinerService miner(MinerAdvisorService advisor, AiConfig aiConfig) {
        return new MinerService(
            new SessionCollector(new SessionRepository(dataSource())),
            advisor,
            aiConfig
        );
    }

    @Test
    void minesEvidenceAndProposalsWhenAiConfigured() {
        seedEvidence();
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

        MiningReport r = miner(advisor, configured()).forFlavor("fake");

        assertTrue(r.aiGenerated());
        assertEquals(2, r.sessionCount());
        assertEquals(2, r.analyzedSessions());
        assertTrue(r.toolSequences().stream().anyMatch(n -> n.name().equals("Read → Edit → Bash")));
        assertTrue(
            r.failureFixes().stream().anyMatch(f -> f.error().equals("Build fails on JDK 21"))
        );
        assertTrue(r.recommendations().stream().anyMatch(n -> n.name().equals("Add a lint rule")));
        assertEquals("read-edit-bash", r.skills().get(0).name());
        assertEquals("Use JDK 25 via mise", r.agentsRules().get(0).rule());
        assertFalse(r.toolingGaps().isEmpty());
    }

    @Test
    void returnsEvidenceOnlyWhenAiNotConfigured() {
        seedEvidence();
        MinerAdvisorService advisor = evidence -> {
            throw new AssertionError("advisor must not be called when AI is not configured");
        };

        MiningReport r = miner(advisor, notConfigured()).forFlavor("fake");

        assertFalse(r.aiGenerated());
        assertTrue(r.note().contains("Configure an AI provider"));
        assertFalse(r.toolSequences().isEmpty());
        assertTrue(r.skills().isEmpty());
        assertTrue(r.agentsRules().isEmpty());
    }

    @Test
    void degradesGracefullyWhenAdvisorFails() {
        seedEvidence();
        MinerAdvisorService advisor = evidence -> {
            throw new RuntimeException("model timeout");
        };

        MiningReport r = miner(advisor, configured()).forFlavor("fake");

        assertFalse(r.aiGenerated());
        assertTrue(r.note().contains("unavailable"));
        assertTrue(r.toolSequences().stream().anyMatch(n -> n.name().equals("Read → Edit → Bash")));
        assertTrue(r.skills().isEmpty());
    }

    @Test
    void reportsWhenThereIsNotEnoughEvidence() {
        // A single session with no recurring sequence and no cached analysis: nothing to mine.
        seedSession("fake", "s1", "[{\"tool_calls\":[{\"name\":\"Read\"}]}]", null, 1L);
        MinerAdvisorService advisor = evidence -> {
            throw new AssertionError("advisor must not be called when there is no evidence");
        };

        MiningReport r = miner(advisor, configured()).forFlavor("fake");

        assertFalse(r.aiGenerated());
        assertTrue(r.note().contains("Not enough recurring patterns"));
        assertTrue(r.toolSequences().isEmpty());
    }
}
