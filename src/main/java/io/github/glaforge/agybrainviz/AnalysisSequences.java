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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a session's stored steps into condensed LLM-analysis input, staying aware of which schema the
 * steps are in.
 *
 * <p>This is the one part of the read path that could not be unified. Antigravity's stored steps are
 * its <em>native</em> schema ({@code USER_INPUT}/{@code PLANNER_RESPONSE}/{@code ERROR_MESSAGE} with
 * {@code tool_calls[].arguments}), which only {@link TranscriptParser} reads. Codex and Claude Code
 * store the adapter (normalized) schema ({@code FUNCTION_CALL}/{@code FUNCTION_OUTPUT} with
 * {@code tool_calls[].args}), which {@link NormalizedSteps} reads. Running the wrong one drops every
 * agent action and error, so the source decides.
 */
final class AnalysisSequences {

    private AnalysisSequences() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Parses the stored steps JSON and derives the analysis sequences for the source's schema. */
    static List<List<String>> fromStepsJson(String source, String stepsJson) {
        return from(source, parseSteps(stepsJson));
    }

    static List<List<String>> from(String source, List<ObjectNode> steps) {
        if (isAntigravity(source)) {
            // TranscriptParser reads the native schema line by line; re-serialize the stored steps.
            List<String> lines = new ArrayList<>(steps.size());
            for (ObjectNode step : steps) {
                lines.add(step.toString());
            }
            return TranscriptParser.parseSequences(lines);
        }
        return NormalizedSteps.toAnalysisSequences(steps);
    }

    private static boolean isAntigravity(String source) {
        return source != null && source.startsWith(AntigravityNormalizer.FLAVOR_PREFIX);
    }

    private static List<ObjectNode> parseSteps(String stepsJson) {
        List<ObjectNode> steps = new ArrayList<>();
        if (stepsJson == null) return steps;
        try {
            JsonNode arr = MAPPER.readTree(stepsJson);
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    if (node instanceof ObjectNode object) {
                        steps.add(object);
                    }
                }
            }
        } catch (Exception e) {
            // A malformed transcript yields no sequences rather than failing analysis.
        }
        return steps;
    }
}
