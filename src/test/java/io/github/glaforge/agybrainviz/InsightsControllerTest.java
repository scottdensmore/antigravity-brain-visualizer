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
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Integration tests for the cross-session insights endpoint, over sessions gathered from the store. */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // required by TestPropertyProvider
class InsightsControllerTest implements TestPropertyProvider {

    @Override
    public Map<String, String> getProperties() {
        return TestPostgres.datasourceProperties();
    }

    @Inject
    @Client("/")
    HttpClient client;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void reset() throws SQLException {
        PostgresTest.resetStore();
    }

    // Turns a JSONL transcript into the stored steps array (Antigravity's native schema, unchanged).
    private static String steps(String jsonl) {
        return "[" + String.join(",", jsonl.strip().split("\n")) + "]";
    }

    private void seedAntigravity(String id, String jsonl, String summaryJson, long mtime) {
        PostgresTest.seedSession(
            "antigravity-cli",
            id,
            "t-" + id,
            steps(jsonl),
            summaryJson,
            mtime
        );
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
        seedAntigravity(
            "session-errors",
            "{\"type\":\"USER_INPUT\",\"source\":\"USER_EXPLICIT\",\"content\":\"go\",\"created_at\":\"2026-06-19T10:00:00Z\"}\n" +
            "{\"type\":\"PLANNER_RESPONSE\",\"source\":\"MODEL\",\"created_at\":\"2026-06-19T10:00:05Z\",\"tool_calls\":[{\"name\":\"Read\",\"arguments\":{}},{\"name\":\"Bash\",\"arguments\":{}}]}\n" +
            "{\"type\":\"ERROR_MESSAGE\",\"status\":\"ERROR\",\"error\":\"NullPointerException at X\",\"created_at\":\"2026-06-19T10:00:10Z\"}",
            "{\"recommendations\":[\"Add a lint rule\",\"Write more tests\"],\"issues\":[{\"error\":\"NPE in parser\",\"circumvention\":\"added null check\"}]}",
            1L
        );
        seedAntigravity(
            "session-clean",
            "{\"type\":\"USER_INPUT\",\"source\":\"USER_EXPLICIT\",\"content\":\"hi\",\"created_at\":\"2026-06-19T11:00:00Z\"}\n" +
            "{\"type\":\"PLANNER_RESPONSE\",\"source\":\"MODEL\",\"created_at\":\"2026-06-19T11:00:01Z\",\"tool_calls\":[{\"name\":\"Read\",\"arguments\":{}}]}",
            null,
            2L
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
    void drillsDownIntoATallyItem() throws IOException {
        seedAntigravity(
            "session-errors",
            "{\"type\":\"USER_INPUT\",\"source\":\"USER_EXPLICIT\",\"content\":\"fix the parser\",\"created_at\":\"2026-06-19T10:00:00Z\"}\n" +
            "{\"type\":\"PLANNER_RESPONSE\",\"source\":\"MODEL\",\"created_at\":\"2026-06-19T10:00:05Z\",\"tool_calls\":[{\"name\":\"Read\",\"arguments\":{}}]}\n" +
            "{\"type\":\"ERROR_MESSAGE\",\"status\":\"ERROR\",\"error\":\"NullPointerException at X\",\"created_at\":\"2026-06-19T10:00:10Z\"}",
            null,
            1L
        );

        JsonNode r = get(
            "/api/insights/sessions?flavor=antigravity-cli&category=error&key=" +
            java.net.URLEncoder.encode(
                "NullPointerException at X",
                java.nio.charset.StandardCharsets.UTF_8
            )
        );

        assertEquals("error", r.get("category").asText());
        assertEquals(1, r.get("totalMatches").asInt());
        assertEquals("session-errors", r.get("sessions").get(0).get("id").asText());
        assertEquals("fix the parser", r.get("sessions").get(0).get("title").asText());
    }

    @Test
    void returnsEmptyReportForUnknownFlavor() throws IOException {
        JsonNode r = get("/api/insights?flavor=antigravity-ide");
        assertEquals(0, r.get("sessionCount").asInt());
        assertEquals(0, r.get("sampledSessions").asInt());
        JsonNode topTools = r.get("topTools");
        assertTrue(topTools == null || topTools.isEmpty());
    }
}
