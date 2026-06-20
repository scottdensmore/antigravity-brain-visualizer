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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Pure transcript-processing helpers used by {@link AnalysisController}.
 *
 * <p>This logic is intentionally free of any I/O, network, or framework dependencies so it can be
 * unit-tested directly: it turns raw JSONL transcript lines into condensed, de-duplicated
 * "sequences" of human-readable summary lines, and splits those lines into chunks that fit within a
 * token budget.
 */
final class TranscriptParser {

    private TranscriptParser() {}

    /**
     * Condenses raw JSONL transcript lines into a list of sequences. A new sequence is started at
     * every user input; within a sequence, agent tool calls and error/exception events are
     * summarized into compact single lines. Each returned sequence has consecutive duplicate lines
     * collapsed (see {@link #deduplicateSequence(List)}). Blank and malformed lines are skipped.
     *
     * @param allLines the raw JSONL lines of a transcript
     * @return one list of summary lines per user-initiated sequence
     */
    static List<List<String>> parseSequences(List<String> allLines) {
        ObjectMapper mapper = new ObjectMapper();
        List<List<String>> sequences = new ArrayList<>();
        List<String> currentSequence = new ArrayList<>();

        for (String line : allLines) {
            if (line.trim().isEmpty()) continue;
            try {
                JsonNode node = mapper.readTree(line);
                String type = node.path("type").asText("");
                if (
                    "USER_INPUT".equals(type) ||
                    "USER_EXPLICIT".equals(node.path("source").asText(""))
                ) {
                    if (!currentSequence.isEmpty()) {
                        sequences.add(deduplicateSequence(currentSequence));
                        currentSequence = new ArrayList<>();
                    }
                    String content = node.path("content").asText("");
                    currentSequence.add(
                        "USER REQUEST: " + content.substring(0, Math.min(2000, content.length()))
                    );
                } else if (
                    "PLANNER_RESPONSE".equals(type) ||
                    "MODEL".equals(node.path("source").asText(""))
                ) {
                    JsonNode tools = node.path("tool_calls");
                    if (!tools.isMissingNode() && tools.isArray()) {
                        for (JsonNode tool : tools) {
                            String name = tool.path("name").asText("unknown");
                            String action = tool.path("arguments").path("toolAction").asText("");
                            String tgt = tool.path("arguments").path("TargetFile").asText("");
                            if (tgt.isEmpty()) tgt =
                                tool.path("arguments").path("CommandLine").asText("");
                            currentSequence.add(
                                "AGENT ACTION: [" + name + "] " + action + " -> " + tgt
                            );
                        }
                    }
                } else if (
                    node.has("error") ||
                    (node.has("content") && node.path("content").asText("").contains("Exception"))
                ) {
                    String err = node.path("content").asText("");
                    currentSequence.add(
                        "SYSTEM EVENT/ERROR: " + err.substring(0, Math.min(500, err.length()))
                    );
                }
            } catch (Exception e) {
                // skip malformed
            }
        }
        if (!currentSequence.isEmpty()) {
            sequences.add(deduplicateSequence(currentSequence));
        }
        return sequences;
    }

    /**
     * Collapses runs of identical consecutive lines into a single line annotated with the repeat
     * count, e.g. {@code "foo (repeated 3 times)"}. Non-consecutive duplicates are left intact.
     *
     * @param sequence the lines to de-duplicate
     * @return a new list with consecutive duplicates collapsed
     */
    static List<String> deduplicateSequence(List<String> sequence) {
        if (sequence.isEmpty()) return sequence;
        List<String> deduped = new ArrayList<>();
        String lastLine = null;
        int count = 0;
        for (String line : sequence) {
            if (line.equals(lastLine)) {
                count++;
            } else {
                if (count > 1) {
                    deduped.set(deduped.size() - 1, lastLine + " (repeated " + count + " times)");
                }
                deduped.add(line);
                lastLine = line;
                count = 1;
            }
        }
        if (count > 1) {
            deduped.set(deduped.size() - 1, lastLine + " (repeated " + count + " times)");
        }
        return deduped;
    }

    /**
     * Recursively splits {@code lines} into chunks whose joined text stays within {@code maxTokens},
     * appending each resulting chunk to {@code safeChunks}. A chunk of a single line is never split
     * further, even if it exceeds the budget. If {@code tokenCounter} throws, a character-length
     * heuristic ({@code length / 2}) is used as a fallback estimate.
     *
     * @param lines the lines to split
     * @param tokenCounter estimates the token count of a string
     * @param maxTokens the maximum tokens allowed per chunk
     * @param safeChunks output accumulator that receives the produced chunks
     */
    static void splitIntoSafeChunks(
        List<String> lines,
        ToIntFunction<String> tokenCounter,
        int maxTokens,
        List<List<String>> safeChunks
    ) {
        if (lines.isEmpty()) return;
        String text = String.join("\n", lines);
        try {
            int tokens = tokenCounter.applyAsInt(text);
            if (tokens <= maxTokens || lines.size() == 1) {
                safeChunks.add(lines);
            } else {
                int mid = lines.size() / 2;
                splitIntoSafeChunks(lines.subList(0, mid), tokenCounter, maxTokens, safeChunks);
                splitIntoSafeChunks(
                    lines.subList(mid, lines.size()),
                    tokenCounter,
                    maxTokens,
                    safeChunks
                );
            }
        } catch (Exception e) {
            int fallbackTokens = text.length() / 2;
            if (fallbackTokens <= maxTokens || lines.size() == 1) {
                safeChunks.add(lines);
            } else {
                int mid = lines.size() / 2;
                splitIntoSafeChunks(lines.subList(0, mid), tokenCounter, maxTokens, safeChunks);
                splitIntoSafeChunks(
                    lines.subList(mid, lines.size()),
                    tokenCounter,
                    maxTokens,
                    safeChunks
                );
            }
        }
    }
}
