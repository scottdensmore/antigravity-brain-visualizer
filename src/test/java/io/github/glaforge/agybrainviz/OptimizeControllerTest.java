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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** Integration tests for the prompt-lab endpoint (no GEMINI_API_KEY => graceful degradation). */
@MicronautTest
class OptimizeControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void servesTheDefaultInstruction() throws IOException {
        JsonNode r = MAPPER.readTree(client.toBlocking().retrieve("/api/optimize"));
        assertTrue(r.get("instruction").asText().toLowerCase().contains("analyze"));
        assertEquals(OptimizeService.MAX_SAMPLE, r.get("maxSample").asInt());
    }

    @Test
    void comparisonDegradesWithoutAnAiKey() throws IOException {
        String body =
            "{\"flavor\":\"antigravity-cli\",\"sampleSize\":3,\"instructionA\":\"a\",\"instructionB\":\"b\"}";
        JsonNode r = MAPPER.readTree(
            client
                .toBlocking()
                .retrieve(
                    HttpRequest.POST("/api/optimize", body).contentType(MediaType.APPLICATION_JSON)
                )
        );
        assertEquals(0, r.get("sampleSize").asInt());
        assertTrue(r.get("note").asText().contains("Configure an AI provider"));
    }
}
