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
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Integration test for the miner endpoint. With no {@code GEMINI_API_KEY} in the test environment, AI
 * is not configured, so the endpoint exercises the graceful "evidence only" path end-to-end
 * (gather → mine → serialize) over sessions gathered from the store.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // required by TestPropertyProvider
class MineControllerTest implements TestPropertyProvider {

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

    // A session whose model step runs Read → Edit → Bash, stored as Antigravity's native steps.
    private static final String STEPS =
        "[{\"type\":\"USER_INPUT\",\"source\":\"USER_EXPLICIT\",\"content\":\"go\",\"created_at\":\"2026-06-19T10:00:00Z\"}," +
        "{\"type\":\"PLANNER_RESPONSE\",\"source\":\"MODEL\",\"created_at\":\"2026-06-19T10:00:05Z\",\"tool_calls\":[{\"name\":\"Read\",\"arguments\":{}},{\"name\":\"Edit\",\"arguments\":{}},{\"name\":\"Bash\",\"arguments\":{}}]}]";

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
        PostgresTest.seedSession("antigravity-cli", "s1", "t", STEPS, summary, 1L);
        PostgresTest.seedSession("antigravity-cli", "s2", "t", STEPS, summary, 2L);

        JsonNode r = MAPPER.readTree(
            client.toBlocking().retrieve("/api/mine?flavor=antigravity-cli")
        );

        assertEquals(2, r.get("sessionCount").asInt());
        assertEquals(2, r.get("analyzedSessions").asInt());
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
