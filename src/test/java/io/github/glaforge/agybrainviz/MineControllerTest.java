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

/**
 * Integration test for the miner endpoint over a temporary {@code user.home}. With no
 * {@code GEMINI_API_KEY} in the test environment, AI is not configured, so the endpoint exercises the
 * graceful "evidence only" path end-to-end (parse → mine → serialize) without any network call.
 */
@MicronautTest
@ResourceLock("user.home")
class MineControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static String originalUserHome;
    private static Path tempHome;

    @BeforeAll
    static void setUpHome() throws IOException {
        originalUserHome = System.getProperty("user.home");
        tempHome = Files.createTempDirectory("agy-mine-test-home");
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

    // A session whose model step runs Read → Edit → Bash, plus an analysis with a failure→fix pair.
    private String transcript() {
        return (
            "{\"type\":\"USER_INPUT\",\"source\":\"USER_EXPLICIT\",\"content\":\"go\",\"created_at\":\"2026-06-19T10:00:00Z\"}\n" +
            "{\"type\":\"PLANNER_RESPONSE\",\"source\":\"MODEL\",\"created_at\":\"2026-06-19T10:00:05Z\",\"tool_calls\":[{\"name\":\"Read\",\"arguments\":{}},{\"name\":\"Edit\",\"arguments\":{}},{\"name\":\"Bash\",\"arguments\":{}}]}\n"
        );
    }

    private boolean containsField(JsonNode arr, String field, String value) {
        return StreamSupport
            .stream(arr.spliterator(), false)
            .anyMatch(n -> n.get(field).asText().contains(value));
    }

    @Test
    void minesStructuralEvidenceAndDegradesWithoutAi() throws IOException {
        String summary =
            "{\"recommendations\":[\"Pin the JDK with mise\"]," +
            "\"issues\":[{\"error\":\"Build fails on JDK 21\",\"circumvention\":\"Install JDK 25\"}]}";
        writeAntigravity("s1", transcript(), summary);
        writeAntigravity("s2", transcript(), summary);

        JsonNode r = MAPPER.readTree(
            client.toBlocking().retrieve("/api/mine?flavor=antigravity-cli")
        );

        assertEquals(2, r.get("sessionCount").asInt());
        assertEquals(2, r.get("analyzedSessions").asInt());
        // AI is not configured in the test environment, so only the evidence is populated.
        assertFalse(r.get("aiGenerated").asBoolean());
        assertTrue(r.get("note").asText().contains("Configure an AI provider"));

        assertTrue(containsField(r.get("toolSequences"), "name", "Read → Edit → Bash"));
        assertTrue(containsField(r.get("failureFixes"), "error", "Build fails on JDK 21"));
        assertTrue(containsField(r.get("recommendations"), "name", "Pin the JDK with mise"));
    }

    @Test
    void reportsNotEnoughEvidenceForUnknownFlavor() throws IOException {
        JsonNode r = MAPPER.readTree(
            client.toBlocking().retrieve("/api/mine?flavor=antigravity-ide")
        );
        assertEquals(0, r.get("sessionCount").asInt());
        assertFalse(r.get("aiGenerated").asBoolean());
        assertTrue(r.get("note").asText().contains("Not enough recurring patterns"));
    }
}
