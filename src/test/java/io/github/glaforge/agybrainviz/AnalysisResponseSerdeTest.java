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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the analysis DTO records serialize and deserialize correctly with Jackson, which is
 * how {@link AnalysisController} writes summaries to disk and back over the wire.
 */
class AnalysisResponseSerdeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void serializesAllFieldsIncludingNestedRecords() throws Exception {
        AnalysisResponse response = new AnalysisResponse(
            "My Title",
            List.of("step one", "step two"),
            List.of(new AgentAction("edit", "edited a file")),
            List.of(new Issue("it broke", "restarted it")),
            List.of("add a CLI tool"),
            "all went fine"
        );

        JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(response));

        assertEquals("My Title", node.get("shortTitle").asText());
        assertEquals("step one", node.get("flow").get(0).asText());
        assertEquals("edit", node.get("agentActions").get(0).get("action").asText());
        assertEquals("edited a file", node.get("agentActions").get(0).get("description").asText());
        assertEquals("it broke", node.get("issues").get(0).get("error").asText());
        assertEquals("restarted it", node.get("issues").get(0).get("circumvention").asText());
        assertEquals("add a CLI tool", node.get("recommendations").get(0).asText());
        assertEquals("all went fine", node.get("summary").asText());
    }

    @Test
    void roundTripsThroughJackson() throws Exception {
        AnalysisResponse original = new AnalysisResponse(
            "Round Trip",
            List.of("a"),
            List.of(new AgentAction("run", "ran a command")),
            List.of(new Issue("oops", "fixed")),
            List.of("rec"),
            "summary text"
        );

        String json = MAPPER.writeValueAsString(original);
        AnalysisResponse restored = MAPPER.readValue(json, AnalysisResponse.class);

        assertEquals(original, restored);
    }
}
