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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Tests the assistant-message budgeting in {@link NormalizedSteps#toAnalysisSequences(List)}. */
class NormalizedStepsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectNode message(String content) {
        ObjectNode step = MAPPER.createObjectNode();
        step.put("type", "MESSAGE");
        step.put("source", "MODEL");
        step.put("content", content);
        return step;
    }

    private ObjectNode user(String content) {
        ObjectNode step = MAPPER.createObjectNode();
        step.put("type", "USER_INPUT");
        step.put("source", "USER_EXPLICIT");
        step.put("content", content);
        return step;
    }

    @Test
    void keepsFinalAssistantMessageAndBudgetsEarlierOnes() {
        List<ObjectNode> steps = new ArrayList<>();
        steps.add(user("go"));
        // Nine non-final assistant messages, each long enough to consume the full per-message cap,
        // then a final one. The 2000-char budget / 500-each cap admits only the first four.
        for (int i = 0; i < 9; i++) {
            steps.add(message("MSG" + i + " " + "x".repeat(600)));
        }
        steps.add(message("FINAL outcome message"));

        String all = NormalizedSteps
            .toAnalysisSequences(steps)
            .stream()
            .flatMap(List::stream)
            .collect(Collectors.joining("\n"));

        // The final message is always kept.
        assertTrue(all.contains("FINAL outcome message"));
        // Early messages within budget are kept...
        assertTrue(all.contains("MSG0"));
        assertTrue(all.contains("MSG3"));
        // ...but ones past the budget are dropped (not the final).
        assertFalse(all.contains("MSG4"));
        assertFalse(all.contains("MSG8"));
    }

    @Test
    void includesTheLoneAssistantMessageAsFinal() {
        List<ObjectNode> steps = List.of(user("hi"), message("only message"));
        String all = NormalizedSteps
            .toAnalysisSequences(steps)
            .stream()
            .flatMap(List::stream)
            .collect(Collectors.joining("\n"));
        assertTrue(all.contains("ASSISTANT: only message"));
    }
}
