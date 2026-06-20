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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Adapts a Claude Code session transcript (JSONL under {@code ~/.claude/projects/<dir>/<uuid>.jsonl})
 * into the step schema the frontend timeline understands (the same shape used for Antigravity and
 * Codex).
 *
 * <p>Each line is {@code {type, message, timestamp, ...}}. Conversation lines are {@code user} and
 * {@code assistant}; a {@code user} message carries either a plain string (the human prompt) or an
 * array of {@code tool_result} blocks (results sent back to the model), while an {@code assistant}
 * message carries an array of {@code text} / {@code thinking} / {@code tool_use} blocks. Other line
 * types ({@code attachment}, {@code system}, {@code summary}, {@code pr-link}, ...) are not part of
 * the rendered timeline, though a {@code summary} line (or the first user prompt) yields the title.
 */
final class ClaudeCodeAdapter {

    private ClaudeCodeAdapter() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static String toTranscriptJson(List<String> lines) {
        try {
            return MAPPER.writeValueAsString(toSteps(lines));
        } catch (Exception e) {
            return "[]";
        }
    }

    static List<ObjectNode> toSteps(List<String> lines) {
        List<ObjectNode> steps = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            try {
                JsonNode root = MAPPER.readTree(line);
                String type = root.path("type").asText("");
                String ts = root.path("timestamp").asText("");
                JsonNode content = root.path("message").path("content");
                if ("user".equals(type)) {
                    addUserSteps(steps, content, ts);
                } else if ("assistant".equals(type)) {
                    addAssistantSteps(steps, content, ts);
                }
            } catch (Exception e) {
                // skip malformed lines
            }
        }
        return steps;
    }

    private static void addUserSteps(List<ObjectNode> steps, JsonNode content, String ts) {
        if (content.isTextual()) {
            String text = content.asText();
            if (text.isBlank()) return;
            ObjectNode step = newStep(ts);
            step.put("type", "USER_INPUT");
            step.put("source", "USER_EXPLICIT");
            step.put("content", text);
            steps.add(step);
            return;
        }
        if (content.isArray()) {
            for (JsonNode block : content) {
                if (!"tool_result".equals(block.path("type").asText(""))) continue;
                String text = textOf(block.path("content"));
                if (text.isBlank()) continue;
                ObjectNode step = newStep(ts);
                step.put("type", "FUNCTION_OUTPUT");
                step.put("source", "TOOL");
                step.put("content", text);
                if (block.path("is_error").asBoolean(false)) {
                    step.put("status", "ERROR");
                }
                steps.add(step);
            }
        }
    }

    private static void addAssistantSteps(List<ObjectNode> steps, JsonNode content, String ts) {
        if (!content.isArray()) return;
        for (JsonNode block : content) {
            switch (block.path("type").asText("")) {
                case "text":
                    {
                        String text = block.path("text").asText("");
                        if (text.isBlank()) break;
                        ObjectNode step = newStep(ts);
                        step.put("type", "MESSAGE");
                        step.put("source", "MODEL");
                        step.put("content", text);
                        steps.add(step);
                        break;
                    }
                case "thinking":
                    {
                        String text = block.path("thinking").asText("");
                        if (text.isBlank()) break;
                        ObjectNode step = newStep(ts);
                        step.put("type", "REASONING");
                        step.put("source", "MODEL");
                        step.put("thinking", text);
                        steps.add(step);
                        break;
                    }
                case "tool_use":
                    {
                        ObjectNode step = newStep(ts);
                        step.put("type", "FUNCTION_CALL");
                        step.put("source", "MODEL");
                        ArrayNode toolCalls = step.putArray("tool_calls");
                        ObjectNode tool = toolCalls.addObject();
                        tool.put("name", block.path("name").asText("unknown"));
                        tool.set(
                            "args",
                            block.has("input") ? block.get("input") : MAPPER.createObjectNode()
                        );
                        steps.add(step);
                        break;
                    }
                default:
                    break;
            }
        }
    }

    static List<List<String>> toAnalysisSequences(List<String> lines) {
        return NormalizedSteps.toAnalysisSequences(toSteps(lines));
    }

    /**
     * Derives a short session title from a {@code summary} line if present, otherwise the first
     * human prompt (a {@code user} line with string content).
     *
     * @param lines the raw JSONL lines of a Claude Code session
     * @return the derived summary text, if any
     */
    static Optional<String> deriveSummary(List<String> lines) {
        String fallback = null;
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            try {
                JsonNode root = MAPPER.readTree(line);
                String type = root.path("type").asText("");
                if ("summary".equals(type)) {
                    String s = root.path("summary").asText("");
                    if (!s.isBlank()) return Optional.of(summarize(s));
                } else if (fallback == null && "user".equals(type)) {
                    JsonNode content = root.path("message").path("content");
                    if (content.isTextual() && !content.asText().isBlank()) {
                        fallback = summarize(content.asText());
                    }
                }
            } catch (Exception e) {
                // skip malformed lines
            }
        }
        return Optional.ofNullable(fallback);
    }

    private static ObjectNode newStep(String ts) {
        ObjectNode step = MAPPER.createObjectNode();
        if (ts != null && !ts.isBlank()) step.put("created_at", ts);
        return step;
    }

    // Tool-result content is either a plain string or an array of {type:"text", text:...} blocks.
    private static String textOf(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                JsonNode text = part.get("text");
                if (text != null && text.isTextual()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(text.asText());
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private static String summarize(String text) {
        String trimmed = text.strip();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed;
    }
}
