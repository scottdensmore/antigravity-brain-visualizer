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
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Drives the ingest endpoints over HTTP, exactly as the agent-ingest CLI will. */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // required by TestPropertyProvider
class IngestControllerTest implements TestPropertyProvider {

    @Override
    public Map<String, String> getProperties() {
        return TestPostgres.datasourceProperties();
    }

    @Inject
    @Client("/")
    HttpClient client;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RAW =
        "{\"type\":\"USER_INPUT\",\"content\":\"hello\",\"created_at\":\"2026-06-19T10:00:00Z\"}\n";

    @BeforeEach
    void clean() throws SQLException {
        PostgresTest.truncate("sessions");
    }

    private JsonNode push(String body) throws IOException {
        return MAPPER.readTree(
            client
                .toBlocking()
                .retrieve(
                    HttpRequest
                        .POST("/api/ingest/sessions", body)
                        .contentType(MediaType.APPLICATION_JSON)
                )
        );
    }

    private String batch(String source, String id, String raw) throws IOException {
        return MAPPER.writeValueAsString(
            java.util.List.of(new IngestSession(source, id, null, 1_700_000_000_000L, raw, null))
        );
    }

    @Test
    void pushesATrajectoryAndReportsItInTheManifest() throws IOException {
        JsonNode result = push(batch("antigravity-cli", "s1", RAW));
        assertEquals(1, result.get("ingested").asInt());
        assertEquals(0, result.get("skipped").asInt());
        assertEquals(0, result.get("failed").asInt());

        JsonNode manifest = MAPPER.readTree(
            client.toBlocking().retrieve("/api/ingest/manifest?source=antigravity-cli")
        );
        assertTrue(manifest.has("s1"));
        // The manifest publishes the server's hash, which is what the client diffs against.
        assertEquals(Ingestor.sha256(RAW), manifest.get("s1").asText());
    }

    @Test
    void acceptsALargeTranscriptOverTheDefaultRequestLimit() throws IOException {
        // Real agent transcripts routinely exceed Micronaut's 10 MB default request size; the server
        // must accept them rather than reject with a 413.
        String bigTranscript =
            "{\"type\":\"USER_INPUT\",\"content\":\"" + "x".repeat(11 * 1024 * 1024) + "\"}\n";
        JsonNode result = push(batch("antigravity-cli", "big", bigTranscript));
        assertEquals(1, result.get("ingested").asInt());
        assertEquals(0, result.get("failed").asInt());
    }

    @Test
    void rePushingIsANoOpOverHttp() throws IOException {
        push(batch("antigravity-cli", "s1", RAW));
        JsonNode again = push(batch("antigravity-cli", "s1", RAW));

        assertEquals(0, again.get("ingested").asInt());
        assertEquals(1, again.get("skipped").asInt());
    }

    @Test
    void theManifestOfAnUnknownSourceIsEmpty() throws IOException {
        JsonNode manifest = MAPPER.readTree(
            client.toBlocking().retrieve("/api/ingest/manifest?source=nothing-here")
        );
        assertTrue(manifest.isEmpty());
    }

    @Test
    void anUnknownSourceIsCountedAsFailedRatherThanCrashingTheBatch() throws IOException {
        JsonNode result = push(batch("borg", "s1", RAW));
        assertEquals(0, result.get("ingested").asInt());
        assertEquals(1, result.get("failed").asInt());
    }
}
