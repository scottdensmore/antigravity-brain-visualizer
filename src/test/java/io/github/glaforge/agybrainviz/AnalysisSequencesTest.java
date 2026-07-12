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

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the one place the read cutover must stay source-aware: turning stored steps into
 * LLM-analysis input. Antigravity's stored steps are its native schema, which only
 * {@link TranscriptParser} understands; {@link NormalizedSteps} understands the adapter schema.
 */
class AnalysisSequencesTest {

    // A realistic Antigravity transcript in its native schema: USER_INPUT, a PLANNER_RESPONSE
    // carrying a tool call, and an error. Its steps are stored verbatim (the normalizer only parses).
    private static final List<String> ANTIGRAVITY_LINES = List.of(
        "{\"type\":\"USER_INPUT\",\"content\":\"fix the parser\",\"created_at\":\"2026-06-19T10:00:00Z\"}",
        "{\"type\":\"PLANNER_RESPONSE\",\"source\":\"MODEL\",\"tool_calls\":[{\"name\":\"edit_file\",\"arguments\":{\"toolAction\":\"edit\",\"TargetFile\":\"parser.js\"}}]}",
        "{\"type\":\"ERROR_MESSAGE\",\"content\":\"Build failed: NullPointerException in parser.js\"}"
    );

    private static String jsonArrayOf(List<String> lines) {
        return "[" + String.join(",", lines) + "]";
    }

    @Test
    void antigravityMatchesTheLegacyTranscriptParserExactly() {
        // Parity: the DB path (stored steps re-serialized) must produce the same analysis input the
        // old file path did (TranscriptParser on the raw lines).
        List<List<String>> legacy = TranscriptParser.parseSequences(ANTIGRAVITY_LINES);
        List<List<String>> viaStore = AnalysisSequences.fromStepsJson(
            "antigravity-cli",
            jsonArrayOf(ANTIGRAVITY_LINES)
        );
        assertEquals(legacy, viaStore);
    }

    @Test
    void antigravityKeepsAgentActionsThatNormalizedStepsWouldDrop() {
        // The whole reason this stays source-aware: NormalizedSteps only reads FUNCTION_CALL steps,
        // so running it on Antigravity's native PLANNER_RESPONSE would silently lose every tool call.
        List<List<String>> seqs = AnalysisSequences.fromStepsJson(
            "antigravity-cli",
            jsonArrayOf(ANTIGRAVITY_LINES)
        );
        String flat = seqs.stream().flatMap(List::stream).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(flat.contains("AGENT ACTION"), "Antigravity tool calls must survive: " + flat);
        assertTrue(flat.contains("SYSTEM EVENT/ERROR"), "Antigravity errors must survive: " + flat);
    }

    @Test
    void antigravityIdeAlsoUsesTheAntigravityPath() {
        List<List<String>> ide = AnalysisSequences.fromStepsJson(
            "antigravity-ide",
            jsonArrayOf(ANTIGRAVITY_LINES)
        );
        assertEquals(TranscriptParser.parseSequences(ANTIGRAVITY_LINES), ide);
    }

    @Test
    void nonAntigravitySourcesUseNormalizedSteps() {
        // Codex/Claude store the adapter (normalized) schema; that path must go through NormalizedSteps.
        String normalized =
            "[" +
            "{\"type\":\"USER_INPUT\",\"content\":\"add a test\"}," +
            "{\"type\":\"FUNCTION_CALL\",\"source\":\"MODEL\",\"tool_calls\":[{\"name\":\"bash\",\"args\":{\"command\":\"go test\"}}]}" +
            "]";
        List<List<String>> viaHelper = AnalysisSequences.fromStepsJson("codex", normalized);
        String flat = viaHelper.stream().flatMap(List::stream).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(flat.contains("USER REQUEST"), flat);
        assertTrue(flat.contains("AGENT ACTION: [bash]"), flat);
    }

    @Test
    void malformedOrEmptyStepsYieldNoSequences() {
        assertTrue(AnalysisSequences.fromStepsJson("codex", "not json").isEmpty());
        assertTrue(AnalysisSequences.fromStepsJson("antigravity-cli", "[]").isEmpty());
    }
}
