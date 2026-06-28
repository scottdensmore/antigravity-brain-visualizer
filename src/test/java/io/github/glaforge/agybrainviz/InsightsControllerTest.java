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

/** Integration tests for the cross-session insights endpoint, over a temporary {@code user.home}. */
@MicronautTest
@ResourceLock("user.home")
class InsightsControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static String originalUserHome;
    private static Path tempHome;

    @BeforeAll
    static void setUpHome() throws IOException {
        originalUserHome = System.getProperty("user.home");
        tempHome = Files.createTempDirectory("agy-insights-test-home");
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
            .anyMatch(n -> n.get("name").asText().contains(name));
    }

    @Test
    void aggregatesAntigravitySessions() throws IOException {
        writeAntigravity(
            "session-errors",
            "{\"type\":\"USER_INPUT\",\"source\":\"USER_EXPLICIT\",\"content\":\"go\",\"created_at\":\"2026-06-19T10:00:00Z\"}\n" +
            "{\"type\":\"PLANNER_RESPONSE\",\"source\":\"MODEL\",\"created_at\":\"2026-06-19T10:00:05Z\",\"tool_calls\":[{\"name\":\"Read\",\"arguments\":{}},{\"name\":\"Bash\",\"arguments\":{}}]}\n" +
            "{\"type\":\"ERROR_MESSAGE\",\"status\":\"ERROR\",\"error\":\"NullPointerException at X\",\"created_at\":\"2026-06-19T10:00:10Z\"}\n",
            "{\"recommendations\":[\"Add a lint rule\",\"Write more tests\"],\"issues\":[{\"error\":\"NPE in parser\",\"circumvention\":\"added null check\"}]}"
        );
        writeAntigravity(
            "session-clean",
            "{\"type\":\"USER_INPUT\",\"source\":\"USER_EXPLICIT\",\"content\":\"hi\",\"created_at\":\"2026-06-19T11:00:00Z\"}\n" +
            "{\"type\":\"PLANNER_RESPONSE\",\"source\":\"MODEL\",\"created_at\":\"2026-06-19T11:00:01Z\",\"tool_calls\":[{\"name\":\"Read\",\"arguments\":{}}]}\n",
            null
        );

        JsonNode r = get("/api/insights?flavor=antigravity-cli");

        assertEquals(2, r.get("sessionCount").asInt());
        assertEquals(2, r.get("sampledSessions").asInt());
        assertEquals(1, r.get("analyzedSessions").asInt());
        assertEquals(3, r.get("toolCallTotal").asInt());
        assertEquals(1, r.get("sessionsWithErrors").asInt());
        assertEquals(1, r.get("cleanSessions").asInt());

        assertEquals("Read", r.get("topTools").get(0).get("name").asText());
        assertEquals(2, r.get("topTools").get(0).get("count").asInt());
        assertTrue(containsName(r.get("topErrors"), "NullPointerException"));
        assertTrue(containsName(r.get("topRecommendations"), "Add a lint rule"));
        assertTrue(containsName(r.get("topIssues"), "NPE in parser"));
    }

    @Test
    void returnsEmptyReportForUnknownFlavor() throws IOException {
        JsonNode r = get("/api/insights?flavor=antigravity-ide");
        assertEquals(0, r.get("sessionCount").asInt());
        assertEquals(0, r.get("sampledSessions").asInt());
        // Empty collections are omitted by serde; absent means "no tools".
        JsonNode topTools = r.get("topTools");
        assertTrue(topTools == null || topTools.isEmpty());
    }
}
