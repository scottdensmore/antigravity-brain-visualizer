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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the Claude Code transcript → timeline-step adapter. */
class ClaudeCodeAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SUMMARY =
        "{\"type\":\"summary\",\"summary\":\"Refactor the parser\",\"leafUuid\":\"x\"}";
    private static final String USER_STRING =
        "{\"type\":\"user\",\"timestamp\":\"2026-06-07T00:44:40.324Z\",\"message\":{\"role\":\"user\",\"content\":\"Fix the bug\"}}";
    private static final String ASSISTANT =
        "{\"type\":\"assistant\",\"timestamp\":\"2026-06-07T00:44:45.000Z\",\"message\":{\"role\":\"assistant\",\"content\":[" +
        "{\"type\":\"thinking\",\"thinking\":\"Let me think\",\"signature\":\"s\"}," +
        "{\"type\":\"text\",\"text\":\"On it.\"}," +
        "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"Read\",\"input\":{\"file_path\":\"/a.txt\"}}]}}";
    private static final String TOOL_RESULT_OK =
        "{\"type\":\"user\",\"timestamp\":\"2026-06-07T00:44:46.000Z\",\"message\":{\"role\":\"user\",\"content\":[" +
        "{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_1\",\"content\":\"file contents\",\"is_error\":false}]}}";
    private static final String TOOL_RESULT_ERR =
        "{\"type\":\"user\",\"timestamp\":\"2026-06-07T00:44:47.000Z\",\"message\":{\"role\":\"user\",\"content\":[" +
        "{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_2\",\"content\":\"boom\",\"is_error\":true}]}}";

    private List<ObjectNode> steps(String... lines) {
        return ClaudeCodeAdapter.toSteps(List.of(lines));
    }

    @Test
    void mapsUserStringToUserInput() {
        ObjectNode s = steps(USER_STRING).get(0);
        assertEquals("USER_INPUT", s.get("type").asText());
        assertEquals("USER_EXPLICIT", s.get("source").asText());
        assertEquals("Fix the bug", s.get("content").asText());
        assertEquals("2026-06-07T00:44:40.324Z", s.get("created_at").asText());
    }

    @Test
    void expandsAssistantBlocksInOrder() {
        List<ObjectNode> s = steps(ASSISTANT);
        assertEquals(3, s.size());
        assertEquals("REASONING", s.get(0).get("type").asText());
        assertEquals("Let me think", s.get(0).get("thinking").asText());
        assertEquals("MESSAGE", s.get(1).get("type").asText());
        assertEquals("On it.", s.get(1).get("content").asText());
        assertEquals("FUNCTION_CALL", s.get(2).get("type").asText());
        JsonNode tool = s.get(2).get("tool_calls").get(0);
        assertEquals("Read", tool.get("name").asText());
        assertEquals("/a.txt", tool.get("args").get("file_path").asText());
    }

    @Test
    void mapsToolResultToFunctionOutput() {
        ObjectNode s = steps(TOOL_RESULT_OK).get(0);
        assertEquals("FUNCTION_OUTPUT", s.get("type").asText());
        assertEquals("TOOL", s.get("source").asText());
        assertEquals("file contents", s.get("content").asText());
        assertFalse(s.has("status"));
    }

    @Test
    void flagsToolResultErrorAsError() {
        assertEquals("ERROR", steps(TOOL_RESULT_ERR).get(0).get("status").asText());
    }

    @Test
    void skipsNonConversationLines() {
        String attachment = "{\"type\":\"attachment\",\"timestamp\":\"t\",\"attachment\":{}}";
        String system = "{\"type\":\"system\",\"timestamp\":\"t\"}";
        assertTrue(steps(SUMMARY, attachment, system).isEmpty());
    }

    @Test
    void preservesOrderAcrossLines() {
        List<ObjectNode> s = steps(USER_STRING, ASSISTANT, TOOL_RESULT_OK);
        // user(1) + assistant(3) + tool_result(1)
        assertEquals(5, s.size());
        assertEquals("USER_INPUT", s.get(0).get("type").asText());
        assertEquals("FUNCTION_OUTPUT", s.get(4).get("type").asText());
    }

    @Test
    void skipsMalformedLines() {
        assertEquals(1, steps("not json", "", USER_STRING).size());
    }

    @Test
    void derivesSummaryFromSummaryLine() {
        assertEquals(
            "Refactor the parser",
            ClaudeCodeAdapter.deriveSummary(List.of(SUMMARY, USER_STRING)).orElseThrow()
        );
    }

    @Test
    void derivesSummaryFromFirstUserPromptWhenNoSummaryLine() {
        assertEquals(
            "Fix the bug",
            ClaudeCodeAdapter.deriveSummary(List.of(ASSISTANT, USER_STRING)).orElseThrow()
        );
    }

    @Test
    void buildsAnalysisSequences() {
        List<List<String>> seqs = ClaudeCodeAdapter.toAnalysisSequences(
            List.of(USER_STRING, ASSISTANT, TOOL_RESULT_OK, TOOL_RESULT_ERR)
        );
        assertEquals(1, seqs.size());
        List<String> lines = seqs.get(0);
        assertTrue(lines.contains("USER REQUEST: Fix the bug"));
        // The final assistant message (the outcome) is kept.
        assertTrue(lines.contains("ASSISTANT: On it."));
        assertTrue(lines.contains("AGENT ACTION: [Read] /a.txt"));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("SYSTEM EVENT/ERROR:")));
        // Successful tool output is omitted.
        assertFalse(lines.stream().anyMatch(l -> l.contains("file contents")));
    }

    @Test
    void producesValidJsonArray() throws Exception {
        JsonNode arr = MAPPER.readTree(
            ClaudeCodeAdapter.toTranscriptJson(List.of(USER_STRING, ASSISTANT))
        );
        assertTrue(arr.isArray());
        assertEquals(4, arr.size());
    }
}
