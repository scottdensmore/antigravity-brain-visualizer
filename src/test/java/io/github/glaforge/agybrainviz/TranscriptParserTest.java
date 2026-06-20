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

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure transcript-processing logic in {@link TranscriptParser}. */
class TranscriptParserTest {

    // ----- deduplicateSequence -----

    @Test
    void deduplicateCollapsesConsecutiveDuplicates() {
        List<String> result = TranscriptParser.deduplicateSequence(List.of("a", "a", "a", "b"));
        assertEquals(List.of("a (repeated 3 times)", "b"), result);
    }

    @Test
    void deduplicateLeavesNonConsecutiveDuplicatesIntact() {
        List<String> result = TranscriptParser.deduplicateSequence(List.of("a", "b", "a"));
        assertEquals(List.of("a", "b", "a"), result);
    }

    @Test
    void deduplicateHandlesTrailingRun() {
        List<String> result = TranscriptParser.deduplicateSequence(List.of("x", "y", "y"));
        assertEquals(List.of("x", "y (repeated 2 times)"), result);
    }

    @Test
    void deduplicateReturnsEmptyForEmpty() {
        assertTrue(TranscriptParser.deduplicateSequence(new ArrayList<>()).isEmpty());
    }

    // ----- parseSequences -----

    @Test
    void parseSequencesEmitsNothingForEmptyInput() {
        assertTrue(TranscriptParser.parseSequences(List.of()).isEmpty());
    }

    @Test
    void parseSequencesStartsNewSequenceOnEachUserInput() {
        List<String> lines = List.of(
            "{\"type\":\"USER_INPUT\",\"content\":\"first\"}",
            "{\"type\":\"USER_INPUT\",\"content\":\"second\"}"
        );
        List<List<String>> sequences = TranscriptParser.parseSequences(lines);
        assertEquals(2, sequences.size());
        assertEquals("USER REQUEST: first", sequences.get(0).get(0));
        assertEquals("USER REQUEST: second", sequences.get(1).get(0));
    }

    @Test
    void parseSequencesTreatsUserExplicitSourceAsUserInput() {
        List<String> lines = List.of("{\"source\":\"USER_EXPLICIT\",\"content\":\"hello\"}");
        List<List<String>> sequences = TranscriptParser.parseSequences(lines);
        assertEquals(1, sequences.size());
        assertEquals("USER REQUEST: hello", sequences.get(0).get(0));
    }

    @Test
    void parseSequencesTruncatesLongUserContentTo2000Chars() {
        String longContent = "x".repeat(2500);
        List<String> lines = List.of(
            "{\"type\":\"USER_INPUT\",\"content\":\"" + longContent + "\"}"
        );
        List<List<String>> sequences = TranscriptParser.parseSequences(lines);
        assertEquals("USER REQUEST: " + "x".repeat(2000), sequences.get(0).get(0));
    }

    @Test
    void parseSequencesExtractsAgentToolCalls() {
        List<String> lines = List.of(
            "{\"type\":\"USER_INPUT\",\"content\":\"do it\"}",
            "{\"type\":\"PLANNER_RESPONSE\",\"tool_calls\":[" +
            "{\"name\":\"edit\",\"arguments\":{\"toolAction\":\"write\",\"TargetFile\":\"/a.txt\"}}]}"
        );
        List<List<String>> sequences = TranscriptParser.parseSequences(lines);
        assertEquals(1, sequences.size());
        assertEquals("AGENT ACTION: [edit] write -> /a.txt", sequences.get(0).get(1));
    }

    @Test
    void parseSequencesFallsBackToCommandLineWhenNoTargetFile() {
        List<String> lines = List.of(
            "{\"type\":\"USER_INPUT\",\"content\":\"do it\"}",
            "{\"source\":\"MODEL\",\"tool_calls\":[" +
            "{\"name\":\"run\",\"arguments\":{\"toolAction\":\"exec\",\"CommandLine\":\"ls -la\"}}]}"
        );
        List<List<String>> sequences = TranscriptParser.parseSequences(lines);
        assertEquals("AGENT ACTION: [run] exec -> ls -la", sequences.get(0).get(1));
    }

    @Test
    void parseSequencesCapturesErrorEvents() {
        List<String> lines = List.of(
            "{\"type\":\"USER_INPUT\",\"content\":\"go\"}",
            "{\"type\":\"ERROR_MESSAGE\",\"error\":\"boom\",\"content\":\"NullPointerException here\"}"
        );
        List<List<String>> sequences = TranscriptParser.parseSequences(lines);
        assertEquals("SYSTEM EVENT/ERROR: NullPointerException here", sequences.get(0).get(1));
    }

    @Test
    void parseSequencesSkipsBlankAndMalformedLines() {
        List<String> lines = new ArrayList<>();
        lines.add("{\"type\":\"USER_INPUT\",\"content\":\"go\"}");
        lines.add("");
        lines.add("   ");
        lines.add("this is not json");
        List<List<String>> sequences = TranscriptParser.parseSequences(lines);
        assertEquals(1, sequences.size());
        assertEquals(1, sequences.get(0).size());
    }

    @Test
    void parseSequencesIgnoresAgentResponseWhenToolCallsNotAnArray() {
        List<String> lines = List.of(
            "{\"type\":\"USER_INPUT\",\"content\":\"go\"}",
            "{\"type\":\"PLANNER_RESPONSE\",\"tool_calls\":\"not-an-array\"}"
        );
        List<List<String>> sequences = TranscriptParser.parseSequences(lines);
        assertEquals(1, sequences.size());
        // Only the user request line; the malformed tool_calls is ignored.
        assertEquals(1, sequences.get(0).size());
    }

    @Test
    void parseSequencesIgnoresUnrelatedLines() {
        List<String> lines = List.of(
            "{\"type\":\"USER_INPUT\",\"content\":\"go\"}",
            "{\"type\":\"SOMETHING_ELSE\",\"source\":\"SYSTEM\",\"content\":\"noise\"}"
        );
        List<List<String>> sequences = TranscriptParser.parseSequences(lines);
        assertEquals(1, sequences.size());
        assertEquals(1, sequences.get(0).size());
    }

    @Test
    void parseSequencesDeduplicatesWithinASequence() {
        List<String> lines = List.of(
            "{\"type\":\"USER_INPUT\",\"content\":\"go\"}",
            "{\"type\":\"PLANNER_RESPONSE\",\"tool_calls\":[" +
            "{\"name\":\"run\",\"arguments\":{\"CommandLine\":\"ls\"}}]}",
            "{\"type\":\"PLANNER_RESPONSE\",\"tool_calls\":[" +
            "{\"name\":\"run\",\"arguments\":{\"CommandLine\":\"ls\"}}]}"
        );
        List<List<String>> sequences = TranscriptParser.parseSequences(lines);
        assertEquals("AGENT ACTION: [run]  -> ls (repeated 2 times)", sequences.get(0).get(1));
    }

    // ----- splitIntoSafeChunks -----

    @Test
    void splitKeepsSingleChunkWhenWithinBudget() {
        ToIntFunction<String> counter = s -> 5;
        List<List<String>> chunks = new ArrayList<>();
        TranscriptParser.splitIntoSafeChunks(List.of("a", "b", "c"), counter, 100, chunks);
        assertEquals(1, chunks.size());
        assertEquals(List.of("a", "b", "c"), chunks.get(0));
    }

    @Test
    void splitDividesWhenOverBudget() {
        // Each individual line counts as 1 token, but any multi-line join counts as over budget.
        ToIntFunction<String> counter = s -> s.contains("\n") ? 1000 : 1;
        List<List<String>> chunks = new ArrayList<>();
        TranscriptParser.splitIntoSafeChunks(List.of("a", "b", "c", "d"), counter, 10, chunks);
        assertEquals(4, chunks.size());
        for (List<String> chunk : chunks) {
            assertEquals(1, chunk.size());
        }
    }

    @Test
    void splitNeverDividesASingleLineEvenIfOverBudget() {
        ToIntFunction<String> counter = s -> 1_000_000;
        List<List<String>> chunks = new ArrayList<>();
        TranscriptParser.splitIntoSafeChunks(List.of("huge"), counter, 10, chunks);
        assertEquals(1, chunks.size());
        assertEquals(List.of("huge"), chunks.get(0));
    }

    @Test
    void splitUsesLengthHeuristicWhenCounterThrows() {
        // Counter always throws -> fallback uses text.length()/2 against the budget.
        ToIntFunction<String> counter = s -> {
            throw new RuntimeException("estimator unavailable");
        };
        List<List<String>> chunks = new ArrayList<>();
        // "aaaa\nbbbb" length 9 -> 4 (<= 4? no, 4<=4 yes) ... ensure it still splits a large input.
        List<String> lines = List.of("a".repeat(20), "b".repeat(20));
        TranscriptParser.splitIntoSafeChunks(lines, counter, 5, chunks);
        // Joined length 41 -> 20 > 5, so it splits into two single-line chunks.
        assertEquals(2, chunks.size());
    }
}
