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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared logic for turning normalized timeline steps (the schema produced by the per-source adapters
 * such as {@link CodexAdapter} and {@link ClaudeCodeAdapter}) into condensed LLM-analysis input: one
 * list of short lines per user-initiated sequence, mirroring
 * {@link TranscriptParser#parseSequences(List)}. User prompts, agent tool calls, and failed tool
 * outputs are summarized; successful outputs and reasoning are omitted. Assistant narration is
 * mostly omitted because its prose dominates verbose sessions, but the <em>final</em> assistant
 * message (the agent's outcome/conclusion) is always kept, plus a small budget of earlier assistant
 * messages — this preserves the stated outcome for the summary while keeping the token footprint
 * small.
 */
final class NormalizedSteps {

    private NormalizedSteps() {}

    // The final assistant message (outcome) is always included up to this length; earlier assistant
    // messages share a small total budget so verbose sessions don't blow up the analysis input.
    private static final int ASSISTANT_FINAL_MAX = 2000;
    private static final int ASSISTANT_OTHER_MAX_EACH = 500;
    private static final int ASSISTANT_OTHER_BUDGET = 2000;

    static List<List<String>> toAnalysisSequences(List<ObjectNode> steps) {
        int lastMessageIndex = lastMessageIndex(steps);
        int assistantBudget = ASSISTANT_OTHER_BUDGET;

        List<List<String>> sequences = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            ObjectNode step = steps.get(i);
            String type = step.path("type").asText("");

            String line;
            if ("MESSAGE".equals(type)) {
                String content = step.path("content").asText("");
                if (content.isBlank()) continue;
                if (i == lastMessageIndex) {
                    line = "ASSISTANT: " + truncate(content, ASSISTANT_FINAL_MAX);
                } else if (assistantBudget > 0) {
                    String text = truncate(
                        content,
                        Math.min(ASSISTANT_OTHER_MAX_EACH, assistantBudget)
                    );
                    assistantBudget -= text.length();
                    line = "ASSISTANT: " + text;
                } else {
                    continue;
                }
            } else {
                line = analysisLine(step, type);
                if (line == null) continue;
            }

            if ("USER_INPUT".equals(type) && !current.isEmpty()) {
                sequences.add(TranscriptParser.deduplicateSequence(current));
                current = new ArrayList<>();
            }
            current.add(line);
        }
        if (!current.isEmpty()) {
            sequences.add(TranscriptParser.deduplicateSequence(current));
        }
        return sequences;
    }

    private static int lastMessageIndex(List<ObjectNode> steps) {
        int idx = -1;
        for (int i = 0; i < steps.size(); i++) {
            ObjectNode step = steps.get(i);
            if (
                "MESSAGE".equals(step.path("type").asText("")) &&
                !step.path("content").asText("").isBlank()
            ) {
                idx = i;
            }
        }
        return idx;
    }

    private static String analysisLine(ObjectNode step, String type) {
        switch (type) {
            case "USER_INPUT":
                return "USER REQUEST: " + truncate(step.path("content").asText(""), 2000);
            case "FUNCTION_CALL":
                {
                    JsonNode tool = step.path("tool_calls").path(0);
                    String name = tool.path("name").asText("unknown");
                    return "AGENT ACTION: [" + name + "] " + argsSummary(tool.path("args"));
                }
            case "FUNCTION_OUTPUT":
                if ("ERROR".equals(step.path("status").asText(""))) {
                    return "SYSTEM EVENT/ERROR: " + truncate(step.path("content").asText(""), 500);
                }
                return null;
            default:
                return null;
        }
    }

    private static String argsSummary(JsonNode args) {
        if (args == null || args.isMissingNode() || args.isNull()) return "";
        // Prefer a human-meaningful field across tools (Codex: cmd; Claude Code: command/file_path).
        for (String key : new String[] { "cmd", "command", "file_path" }) {
            JsonNode value = args.get(key);
            if (value != null && value.isTextual()) {
                return truncate(value.asText(), 200);
            }
        }
        return truncate(args.toString(), 200);
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) : text;
    }
}
