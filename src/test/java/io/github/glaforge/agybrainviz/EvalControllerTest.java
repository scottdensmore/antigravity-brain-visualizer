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
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
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

/** Integration test for the eval endpoint: sessions and run history come from the shared store. */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // required by TestPropertyProvider
class EvalControllerTest implements TestPropertyProvider {

    /** Point the application context at the test container, not a developer's local Postgres. */
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

    private static final String STEPS =
        "[{\"type\":\"USER_INPUT\",\"content\":\"go\",\"created_at\":\"2026-06-19T10:00:00Z\"}]";

    private void seedAntigravity(String id, String summaryJson, long mtime) {
        PostgresTest.seedSession("antigravity-cli", id, "t-" + id, STEPS, summaryJson, mtime);
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
        seedAntigravity(
            "s-good",
            "{\"shortTitle\":\"Fixed build\",\"summary\":\"Updated the JDK and it passed.\"," +
            "\"flow\":[\"Read config\"],\"recommendations\":[\"Pin the JDK\"]," +
            "\"issues\":[{\"error\":\"Build failed\",\"circumvention\":\"Used JDK 25\"}]}",
            1L
        );
        seedAntigravity("s-poor", "{\"summary\":\"\"}", 2L);
        seedAntigravity("s-none", null, 3L);

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
        seedAntigravity(
            "s-good",
            "{\"shortTitle\":\"t\",\"summary\":\"s\",\"flow\":[\"f\"],\"recommendations\":[\"r\"]}",
            1L
        );

        JsonNode r = get("/api/eval?flavor=antigravity-cli&judge=true");

        // No GEMINI_API_KEY in the test environment, so the judge degrades gracefully.
        assertFalse(r.get("judge").get("ran").asBoolean());
        assertTrue(r.get("judge").get("note").asText().contains("Configure an AI provider"));
    }

    @Test
    void savesAndListsRunHistory() throws IOException {
        // Round-trips the persistence path; no seeded sessions needed (and adding a session dir here
        // would perturb the exact session counts other tests assert on this shared temp home).
        String reportJson = client.toBlocking().retrieve("/api/eval?flavor=antigravity-cli");
        String savedJson = client
            .toBlocking()
            .retrieve(
                HttpRequest
                    .POST("/api/eval/runs", reportJson)
                    .contentType(MediaType.APPLICATION_JSON)
            );
        JsonNode saved = MAPPER.readTree(savedJson);
        assertEquals("antigravity-cli", saved.get("flavor").asText());
        assertTrue(saved.has("savedAt"));

        JsonNode history = get("/api/eval/runs?flavor=antigravity-cli");
        assertTrue(history.isArray());
        assertTrue(history.size() >= 1);
        assertEquals("antigravity-cli", history.get(0).get("flavor").asText());

        // Delete the run we just saved and confirm it is gone.
        String savedAt = saved.get("savedAt").asText();
        JsonNode del = MAPPER.readTree(
            client
                .toBlocking()
                .retrieve(
                    HttpRequest.DELETE(
                        "/api/eval/runs?savedAt=" +
                        java.net.URLEncoder.encode(savedAt, java.nio.charset.StandardCharsets.UTF_8)
                    )
                )
        );
        assertEquals(1, del.get("deleted").asInt());
        JsonNode after = get("/api/eval/runs?flavor=antigravity-cli");
        assertFalse(
            StreamSupport
                .stream(after.spliterator(), false)
                .anyMatch(n -> savedAt.equals(n.get("savedAt").asText()))
        );
    }

    @Test
    void returnsEmptyScoreboardForUnknownFlavor() throws IOException {
        JsonNode r = get("/api/eval?flavor=antigravity-ide");
        assertEquals(0, r.get("sessionCount").asInt());
        assertEquals(0, r.get("evaluatedSessions").asInt());
        assertEquals(0.0, r.get("avgScore").asDouble());
    }
}
