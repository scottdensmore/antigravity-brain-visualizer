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
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/** Integration test for the eval endpoint over a temporary {@code user.home}. */
@MicronautTest
@ResourceLock("user.home")
class EvalControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static String originalUserHome;
    private static Path tempHome;

    @BeforeAll
    static void setUpHome() throws IOException {
        originalUserHome = System.getProperty("user.home");
        tempHome = Files.createTempDirectory("agy-eval-test-home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterAll
    static void restoreHome() {
        if (originalUserHome != null) System.setProperty("user.home", originalUserHome);
    }

    private void writeAntigravity(String id, String transcript, String summaryJson)
        throws IOException {
        Path logs = tempHome
            .resolve(".gemini")
            .resolve("antigravity-cli")
            .resolve("brain")
            .resolve(id)
            .resolve(".system_generated")
            .resolve("logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("transcript.jsonl"), transcript);
        if (summaryJson != null) Files.writeString(logs.resolve("summary.json"), summaryJson);
    }

    private JsonNode get(String uri) throws IOException {
        return MAPPER.readTree(client.toBlocking().retrieve(uri));
    }

    private boolean containsName(JsonNode arr, String name) {
        return StreamSupport
            .stream(arr.spliterator(), false)
            .anyMatch(n -> n.get("name").asText().equals(name));
    }

    @Test
    void scoresCachedAnalysesForASource() throws IOException {
        String transcript =
            "{\"type\":\"USER_INPUT\",\"content\":\"go\",\"created_at\":\"2026-06-19T10:00:00Z\"}\n";
        writeAntigravity(
            "s-good",
            transcript,
            "{\"shortTitle\":\"Fixed build\",\"summary\":\"Updated the JDK and it passed.\"," +
            "\"flow\":[\"Read config\"],\"recommendations\":[\"Pin the JDK\"]," +
            "\"issues\":[{\"error\":\"Build failed\",\"circumvention\":\"Used JDK 25\"}]}"
        );
        writeAntigravity("s-poor", transcript, "{\"summary\":\"\"}");
        writeAntigravity("s-none", transcript, null);

        JsonNode r = get("/api/eval?flavor=antigravity-cli");

        assertEquals(3, r.get("sessionCount").asInt());
        assertEquals(2, r.get("evaluatedSessions").asInt());
        assertTrue(r.get("avgScore").asDouble() > 0.0);
        assertTrue(r.get("modelLabel").asText().contains("gemini"));
        assertTrue(containsName(r.get("checkPassRates"), "schema-complete"));
        // The empty analysis is the lowest-scoring case.
        assertEquals("s-poor", r.get("worstCases").get(0).get("sessionId").asText());
        // Judge is opt-in: absent by default, with a prompt to run it.
        assertFalse(r.get("judge").get("ran").asBoolean());
        assertTrue(r.get("judge").get("note").asText().contains("Run the LLM judge"));
    }

    @Test
    void judgeRequestedDegradesWithoutAiKey() throws IOException {
        writeAntigravity(
            "s-good",
            "{\"type\":\"USER_INPUT\",\"content\":\"go\",\"created_at\":\"2026-06-19T10:00:00Z\"}\n",
            "{\"shortTitle\":\"t\",\"summary\":\"s\",\"flow\":[\"f\"],\"recommendations\":[\"r\"]}"
        );

        JsonNode r = get("/api/eval?flavor=antigravity-cli&judge=true");

        // No GEMINI_API_KEY in the test environment, so the judge degrades gracefully.
        assertFalse(r.get("judge").get("ran").asBoolean());
        assertTrue(r.get("judge").get("note").asText().contains("Configure an AI provider"));
    }

    @Test
    void returnsEmptyScoreboardForUnknownFlavor() throws IOException {
        JsonNode r = get("/api/eval?flavor=antigravity-ide");
        assertEquals(0, r.get("sessionCount").asInt());
        assertEquals(0, r.get("evaluatedSessions").asInt());
        assertEquals(0.0, r.get("avgScore").asDouble());
    }
}
