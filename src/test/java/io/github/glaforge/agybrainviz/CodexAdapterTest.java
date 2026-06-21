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

/** Unit tests for the Codex rollout → timeline-step adapter. */
class CodexAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SESSION_META =
        "{\"type\":\"session_meta\",\"timestamp\":\"2026-06-20T19:48:24.520Z\",\"payload\":{\"id\":\"abc\",\"cwd\":\"/repo\"}}";
    private static final String USER_MSG =
        "{\"type\":\"response_item\",\"timestamp\":\"2026-06-20T19:51:13.373Z\",\"payload\":{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Fix the bug\"}]}}";
    private static final String ASSISTANT_MSG =
        "{\"type\":\"response_item\",\"timestamp\":\"2026-06-20T19:51:21.937Z\",\"payload\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"On it.\"}]}}";
    private static final String FUNCTION_CALL =
        "{\"type\":\"response_item\",\"timestamp\":\"2026-06-20T19:51:22.000Z\",\"payload\":{\"type\":\"function_call\",\"name\":\"exec_command\",\"arguments\":\"{\\\"cmd\\\":\\\"git status\\\"}\",\"call_id\":\"call_1\"}}";
    private static final String FUNCTION_OUTPUT_OK =
        "{\"type\":\"response_item\",\"timestamp\":\"2026-06-20T19:51:22.100Z\",\"payload\":{\"type\":\"function_call_output\",\"call_id\":\"call_1\",\"output\":\"Process exited with code 0\\nOutput:\\nclean\"}}";
    private static final String FUNCTION_OUTPUT_FAIL =
        "{\"type\":\"response_item\",\"timestamp\":\"2026-06-20T19:51:23.000Z\",\"payload\":{\"type\":\"function_call_output\",\"call_id\":\"call_2\",\"output\":\"Process exited with code 1\\nboom\"}}";
    private static final String EVENT_USER_MESSAGE =
        "{\"type\":\"event_msg\",\"timestamp\":\"2026-06-20T19:51:13.537Z\",\"payload\":{\"type\":\"user_message\",\"message\":\"Review the branch diff\"}}";

    private List<ObjectNode> steps(String... lines) {
        return CodexAdapter.toSteps(List.of(lines));
    }

    @Test
    void mapsUserMessageToUserInput() {
        ObjectNode s = steps(USER_MSG).get(0);
        assertEquals("USER_INPUT", s.get("type").asText());
        assertEquals("USER_EXPLICIT", s.get("source").asText());
        assertEquals("Fix the bug", s.get("content").asText());
        assertEquals("2026-06-20T19:51:13.373Z", s.get("created_at").asText());
    }

    @Test
    void mapsAssistantMessageToModelMessage() {
        ObjectNode s = steps(ASSISTANT_MSG).get(0);
        assertEquals("MESSAGE", s.get("type").asText());
        assertEquals("MODEL", s.get("source").asText());
        assertEquals("On it.", s.get("content").asText());
    }

    @Test
    void mapsFunctionCallToToolCallWithParsedArgs() {
        ObjectNode s = steps(FUNCTION_CALL).get(0);
        assertEquals("FUNCTION_CALL", s.get("type").asText());
        assertEquals("MODEL", s.get("source").asText());
        JsonNode tool = s.get("tool_calls").get(0);
        assertEquals("exec_command", tool.get("name").asText());
        // Arguments JSON string is parsed into an object so the UI can pretty-print it.
        assertEquals("git status", tool.get("args").get("cmd").asText());
    }

    @Test
    void mapsFunctionOutputToToolOutput() {
        ObjectNode s = steps(FUNCTION_OUTPUT_OK).get(0);
        assertEquals("FUNCTION_OUTPUT", s.get("type").asText());
        assertEquals("TOOL", s.get("source").asText());
        assertTrue(s.get("content").asText().contains("clean"));
        assertFalse(s.has("status"), "a successful command must not be flagged as an error");
    }

    @Test
    void flagsNonZeroExitCodeAsError() {
        ObjectNode s = steps(FUNCTION_OUTPUT_FAIL).get(0);
        assertEquals("ERROR", s.get("status").asText());
    }

    @Test
    void doesNotFlagErrorWhenOutputMerelyEchoesTheExitPhrase() {
        String echoed =
            "{\"type\":\"response_item\",\"timestamp\":\"t\",\"payload\":{\"type\":\"function_call_output\",\"call_id\":\"c\",\"output\":\"log: see 'Process exited with code 1' in docs\\nProcess exited with code 0\"}}";
        assertFalse(steps(echoed).get(0).has("status"));
    }

    @Test
    void stringifiesObjectFunctionOutput() {
        String objOutput =
            "{\"type\":\"response_item\",\"timestamp\":\"t\",\"payload\":{\"type\":\"function_call_output\",\"call_id\":\"c\",\"output\":{\"stdout\":\"ok\"}}}";
        ObjectNode s = steps(objOutput).get(0);
        assertTrue(s.get("content").asText().contains("stdout"));
        assertTrue(s.get("content").asText().contains("ok"));
    }

    @Test
    void skipsSessionMetaAndEmptyReasoning() {
        String emptyReasoning =
            "{\"type\":\"response_item\",\"timestamp\":\"t\",\"payload\":{\"type\":\"reasoning\",\"summary\":[]}}";
        List<ObjectNode> steps = steps(SESSION_META, emptyReasoning, EVENT_USER_MESSAGE);
        assertTrue(steps.isEmpty(), "meta, empty reasoning, and event_msg are not timeline steps");
    }

    @Test
    void emitsReasoningStepWhenSummaryHasText() {
        String reasoning =
            "{\"type\":\"response_item\",\"timestamp\":\"t\",\"payload\":{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"Thinking hard\"}]}}";
        ObjectNode s = steps(reasoning).get(0);
        assertEquals("REASONING", s.get("type").asText());
        assertEquals("Thinking hard", s.get("thinking").asText());
    }

    @Test
    void preservesOrderAcrossMixedLines() {
        List<ObjectNode> steps = steps(
            SESSION_META,
            USER_MSG,
            ASSISTANT_MSG,
            FUNCTION_CALL,
            FUNCTION_OUTPUT_OK
        );
        assertEquals(4, steps.size());
        assertEquals("USER_INPUT", steps.get(0).get("type").asText());
        assertEquals("MESSAGE", steps.get(1).get("type").asText());
        assertEquals("FUNCTION_CALL", steps.get(2).get("type").asText());
        assertEquals("FUNCTION_OUTPUT", steps.get(3).get("type").asText());
    }

    @Test
    void skipsMalformedLines() {
        List<ObjectNode> steps = steps("not json", "", USER_MSG);
        assertEquals(1, steps.size());
    }

    @Test
    void derivesSummaryFromCleanUserMessageEvent() {
        assertEquals(
            "Review the branch diff",
            CodexAdapter.deriveSummary(List.of(USER_MSG, EVENT_USER_MESSAGE)).orElseThrow()
        );
    }

    @Test
    void derivesSummaryFromUserResponseItemWhenNoEvent() {
        assertEquals("Fix the bug", CodexAdapter.deriveSummary(List.of(USER_MSG)).orElseThrow());
    }

    @Test
    void buildsAnalysisSequencesGroupedByUserInput() {
        List<List<String>> seqs = CodexAdapter.toAnalysisSequences(
            List.of(
                USER_MSG,
                ASSISTANT_MSG,
                FUNCTION_CALL,
                FUNCTION_OUTPUT_OK,
                FUNCTION_OUTPUT_FAIL
            )
        );
        assertEquals(1, seqs.size());
        List<String> lines = seqs.get(0);
        assertTrue(lines.get(0).startsWith("USER REQUEST: Fix the bug"));
        // The final assistant message (the outcome) is kept.
        assertTrue(lines.contains("ASSISTANT: On it."));
        assertTrue(lines.contains("AGENT ACTION: [exec_command] git status"));
        // Successful output is omitted; the failed one becomes a SYSTEM EVENT/ERROR line.
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("SYSTEM EVENT/ERROR:")));
        assertFalse(lines.stream().anyMatch(l -> l.contains("clean")));
    }

    @Test
    void analysisLineFallsBackToFullArgsWhenNoKnownField() {
        // No cmd/command/file_path key -> the whole args object is summarized.
        String fc =
            "{\"type\":\"response_item\",\"timestamp\":\"t\",\"payload\":{\"type\":\"function_call\",\"name\":\"grep\",\"arguments\":\"{\\\"pattern\\\":\\\"abc\\\"}\",\"call_id\":\"c\"}}";
        List<List<String>> seqs = CodexAdapter.toAnalysisSequences(List.of(USER_MSG, fc));
        assertTrue(seqs.get(0).get(1).contains("pattern"));
    }

    @Test
    void analysisSequencesSplitOnEachUserInput() {
        String secondUser =
            "{\"type\":\"response_item\",\"timestamp\":\"t\",\"payload\":{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"again\"}]}}";
        List<List<String>> seqs = CodexAdapter.toAnalysisSequences(
            List.of(USER_MSG, ASSISTANT_MSG, secondUser)
        );
        assertEquals(2, seqs.size());
    }

    @Test
    void producesValidJsonArray() throws Exception {
        JsonNode arr = MAPPER.readTree(
            CodexAdapter.toTranscriptJson(List.of(USER_MSG, FUNCTION_CALL))
        );
        assertTrue(arr.isArray());
        assertEquals(2, arr.size());
    }
}
