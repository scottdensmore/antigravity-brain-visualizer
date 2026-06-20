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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapts an OpenAI Codex CLI session "rollout" file into the step schema the frontend timeline
 * already understands (the same shape produced for Antigravity transcripts).
 *
 * <p>Each rollout line is {@code {type, timestamp, payload}}. The conversation is carried by
 * {@code response_item} lines (OpenAI Responses-API items): {@code message} (user/assistant),
 * {@code function_call}, {@code function_call_output}, and {@code reasoning}. Other line types
 * ({@code session_meta}, {@code turn_context}, {@code event_msg}) are not part of the rendered
 * timeline, though {@code event_msg/user_message} is used to derive a clean session title.
 */
final class CodexAdapter {

    private CodexAdapter() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Anchored to line start (multiline) so command output that merely echoes the phrase doesn't
    // get misread as a failure. Detection is a heuristic on Codex's exec-output framing.
    private static final Pattern EXIT_CODE = Pattern.compile(
        "(?m)^Process exited with code (\\d+)"
    );

    /**
     * Converts rollout lines into a JSON array string of timeline steps.
     *
     * @param lines the raw JSONL lines of a Codex rollout file
     * @return a JSON array (as a string) of step objects
     */
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
                if (!"response_item".equals(root.path("type").asText(""))) continue;

                String ts = root.path("timestamp").asText("");
                JsonNode payload = root.path("payload");
                ObjectNode step = stepFor(payload, ts);
                if (step != null) steps.add(step);
            } catch (Exception e) {
                // skip malformed lines
            }
        }
        return steps;
    }

    private static ObjectNode stepFor(JsonNode payload, String ts) {
        String payloadType = payload.path("type").asText("");
        switch (payloadType) {
            case "message":
                return messageStep(payload, ts);
            case "function_call":
                return functionCallStep(payload, ts);
            case "function_call_output":
                return functionOutputStep(payload, ts);
            case "reasoning":
                return reasoningStep(payload, ts);
            default:
                return null;
        }
    }

    private static ObjectNode messageStep(JsonNode payload, String ts) {
        String role = payload.path("role").asText("");
        String text = joinTextParts(payload.path("content"));
        if (text.isBlank()) return null;
        ObjectNode step = newStep(ts);
        if ("user".equals(role)) {
            step.put("type", "USER_INPUT");
            step.put("source", "USER_EXPLICIT");
        } else {
            step.put("type", "MESSAGE");
            step.put("source", "MODEL");
        }
        step.put("content", text);
        return step;
    }

    private static ObjectNode functionCallStep(JsonNode payload, String ts) {
        ObjectNode step = newStep(ts);
        step.put("type", "FUNCTION_CALL");
        step.put("source", "MODEL");
        ArrayNode toolCalls = step.putArray("tool_calls");
        ObjectNode tool = toolCalls.addObject();
        tool.put("name", payload.path("name").asText("unknown"));
        tool.set("args", parseArguments(payload.path("arguments").asText("")));
        return step;
    }

    private static ObjectNode functionOutputStep(JsonNode payload, String ts) {
        String output = outputText(payload.path("output"));
        if (output.isBlank()) return null;
        ObjectNode step = newStep(ts);
        step.put("type", "FUNCTION_OUTPUT");
        step.put("source", "TOOL");
        step.put("content", output);
        if (isFailure(output)) {
            step.put("status", "ERROR");
        }
        return step;
    }

    private static ObjectNode reasoningStep(JsonNode payload, String ts) {
        String text = joinTextParts(payload.path("summary"));
        // Reasoning summaries are frequently empty/encrypted; only emit a step when there is text.
        if (text.isBlank()) return null;
        ObjectNode step = newStep(ts);
        step.put("type", "REASONING");
        step.put("source", "MODEL");
        step.put("thinking", text);
        return step;
    }

    /**
     * Condenses a Codex rollout into LLM-analysis input: one list of short lines per user-initiated
     * sequence, mirroring {@link TranscriptParser#parseSequences(List)} for Antigravity. User
     * prompts, assistant messages, tool calls, and failed tool outputs are summarized; successful
     * outputs and empty reasoning are omitted to keep the token footprint small.
     *
     * @param lines the raw JSONL lines of a Codex rollout file
     * @return one list of summary lines per sequence
     */
    static List<List<String>> toAnalysisSequences(List<String> lines) {
        return NormalizedSteps.toAnalysisSequences(toSteps(lines));
    }

    /**
     * Derives a short session title from the first clean user prompt ({@code event_msg/user_message}),
     * falling back to the first user {@code response_item} message.
     *
     * @param lines the raw JSONL lines of a Codex rollout file
     * @return the derived summary text, if any
     */
    static Optional<String> deriveSummary(List<String> lines) {
        String fallback = null;
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            try {
                JsonNode root = MAPPER.readTree(line);
                JsonNode payload = root.path("payload");
                String outer = root.path("type").asText("");
                if (
                    "event_msg".equals(outer) &&
                    "user_message".equals(payload.path("type").asText(""))
                ) {
                    String msg = payload.path("message").asText("");
                    if (!msg.isBlank()) return Optional.of(summarize(msg));
                } else if (
                    fallback == null &&
                    "response_item".equals(outer) &&
                    "message".equals(payload.path("type").asText("")) &&
                    "user".equals(payload.path("role").asText(""))
                ) {
                    String text = joinTextParts(payload.path("content"));
                    if (!text.isBlank()) fallback = summarize(text);
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

    private static String joinTextParts(JsonNode parts) {
        if (parts == null || !parts.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : parts) {
            JsonNode text = part.get("text");
            if (text != null && text.isTextual()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(text.asText());
            }
        }
        return sb.toString();
    }

    private static JsonNode parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(arguments);
        } catch (Exception e) {
            return MAPPER.getNodeFactory().textNode(arguments);
        }
    }

    private static String outputText(JsonNode output) {
        if (output == null || output.isNull() || output.isMissingNode()) return "";
        return output.isTextual() ? output.asText() : output.toString();
    }

    private static boolean isFailure(String output) {
        Matcher m = EXIT_CODE.matcher(output);
        return m.find() && !"0".equals(m.group(1));
    }

    private static String summarize(String text) {
        String trimmed = text.strip();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed;
    }
}
