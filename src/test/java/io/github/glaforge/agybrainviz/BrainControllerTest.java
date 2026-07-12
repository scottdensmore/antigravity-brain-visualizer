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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Integration tests for {@link BrainController}: the browse-sessions and load-transcript journeys are
 * served from the store, while the inline file preview stays machine-local and reads a controlled
 * fake {@code ~/.gemini}. Title derivation, transcript normalization, and newest-first ordering now
 * happen at ingest, so they are covered by the normalizer and ingest tests, not here.
 */
@MicronautTest
@ResourceLock("user.home")
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // required by TestPropertyProvider
class BrainControllerTest implements TestPropertyProvider {

    @Override
    public Map<String, String> getProperties() {
        return TestPostgres.datasourceProperties();
    }

    @Inject
    @Client("/")
    HttpClient client;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static String originalUserHome;
    private static Path tempHome;

    @BeforeAll
    void setUpHome() throws IOException {
        originalUserHome = System.getProperty("user.home");
        tempHome = Files.createTempDirectory("agy-brain-test-home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterAll
    void restoreHome() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @BeforeEach
    void resetStore() throws SQLException {
        PostgresTest.resetStore();
    }

    private String get(String uri) {
        return client.toBlocking().retrieve(uri);
    }

    // ----- listConversations -----

    @Test
    void listReturnsAnEmptyPageForAFlavorWithNoSessions() throws IOException {
        JsonNode page = MAPPER.readTree(get("/api/brain/conversations?flavor=nothing-here"));
        assertTrue(page.get("items").isEmpty());
        assertEquals(0, page.get("total").asInt());
    }

    @Test
    void listReturnsStoredSessionsNewestFirst() throws IOException {
        PostgresTest.seedSession("list-flavor", "older", "Old work", "[]", null, 1_000L);
        PostgresTest.seedSession("list-flavor", "newer", "New work", "[]", null, 2_000L);

        JsonNode page = MAPPER.readTree(get("/api/brain/conversations?flavor=list-flavor"));
        JsonNode arr = page.get("items");
        assertEquals(2, arr.size());
        assertEquals(2, page.get("total").asInt());
        assertEquals("newer", arr.get(0).get("id").asText());
        assertEquals("New work", arr.get(0).get("summary").asText());
        assertEquals("2000", arr.get(0).get("updatedAt").asText());
        assertEquals("older", arr.get(1).get("id").asText());
    }

    @Test
    void listIsScopedToTheRequestedFlavor() throws IOException {
        PostgresTest.seedSession("codex", "c1", "codex work", "[]", null, 1_000L);
        PostgresTest.seedSession("claude-code", "cc1", "claude work", "[]", null, 1_000L);

        JsonNode arr = MAPPER.readTree(get("/api/brain/conversations?flavor=codex")).get("items");
        assertEquals(1, arr.size());
        assertEquals("c1", arr.get(0).get("id").asText());
    }

    @Test
    void listPagesWithLimitAndOffsetAndReportsTheTotal() throws IOException {
        PostgresTest.seedSession("paged", "a", "A", "[]", null, 3_000L); // newest
        PostgresTest.seedSession("paged", "b", "B", "[]", null, 2_000L);
        PostgresTest.seedSession("paged", "c", "C", "[]", null, 1_000L); // oldest

        JsonNode page1 = MAPPER.readTree(
            get("/api/brain/conversations?flavor=paged&limit=2&offset=0")
        );
        assertEquals(2, page1.get("items").size());
        assertEquals(3, page1.get("total").asInt()); // total is the whole source, not the page
        assertEquals("a", page1.get("items").get(0).get("id").asText());

        JsonNode page2 = MAPPER.readTree(
            get("/api/brain/conversations?flavor=paged&limit=2&offset=2")
        );
        assertEquals(1, page2.get("items").size());
        assertEquals("c", page2.get("items").get(0).get("id").asText());
    }

    @Test
    void listClampsAnOversizedLimitToTheHardCap() throws IOException {
        JsonNode page = MAPPER.readTree(get("/api/brain/conversations?flavor=paged&limit=999999"));
        assertEquals(BrainController.MAX_LIMIT, page.get("limit").asInt());
    }

    @Test
    void listReturnsAnEmptyPageForAnOffsetPastTheEndButKeepsTheTotal() throws IOException {
        PostgresTest.seedSession("edge", "only", "Only one", "[]", null, 1_000L);

        JsonNode page = MAPPER.readTree(
            get("/api/brain/conversations?flavor=edge&limit=10&offset=50")
        );
        assertTrue(page.get("items").isEmpty());
        assertEquals(1, page.get("total").asInt()); // the total still reflects the whole source
    }

    // ----- getTranscript -----

    @Test
    void transcriptReturnsEmptyArrayWhenMissing() {
        assertEquals(
            "[]",
            get("/api/brain/conversations/missing-id/transcript?flavor=nothing").trim()
        );
    }

    @Test
    void transcriptReturnsTheStoredSteps() throws IOException {
        String steps =
            "[{\"type\":\"USER_INPUT\",\"content\":\"hi\"},{\"type\":\"MESSAGE\",\"content\":\"ok\"}]";
        PostgresTest.seedSession("t-flavor", "conv-1", "t", steps, null, 1_000L);

        JsonNode arr = MAPPER.readTree(
            get("/api/brain/conversations/conv-1/transcript?flavor=t-flavor")
        );
        assertTrue(arr.isArray());
        assertEquals(2, arr.size());
        assertEquals("USER_INPUT", arr.get(0).get("type").asText());
        assertEquals("ok", arr.get(1).get("content").asText());
    }

    // ----- getFileContent (still machine-local) -----

    @Test
    void fileReturnsContentForPathInsideGeminiDir() throws IOException {
        Path geminiFile = tempHome.resolve(".gemini").resolve("notes.txt");
        Files.createDirectories(geminiFile.getParent());
        Files.writeString(geminiFile, "hello file");

        assertEquals("hello file", get("/api/brain/file?path=" + geminiFile));
    }

    @Test
    void fileIsUnauthorizedForPathOutsideGeminiDir() throws IOException {
        Path outside = tempHome.resolve("outside-secret.txt");
        Files.writeString(outside, "secret");

        HttpClientResponseException ex = assertThrows(
            HttpClientResponseException.class,
            () -> get("/api/brain/file?path=" + outside)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    void fileIsUnauthorizedForPathTraversalEscapingGeminiDir() throws IOException {
        Path outside = tempHome.resolve("outside-secret.txt");
        Files.writeString(outside, "secret");
        String traversal = tempHome
            .resolve(".gemini")
            .resolve("..")
            .resolve("outside-secret.txt")
            .toString();

        HttpClientResponseException ex = assertThrows(
            HttpClientResponseException.class,
            () -> get("/api/brain/file?path=" + traversal)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    void fileIsUnauthorizedForSiblingDirectoryWithGeminiPrefix() throws IOException {
        Path siblingFile = tempHome.resolve(".gemini-evil").resolve("loot.txt");
        Files.createDirectories(siblingFile.getParent());
        Files.writeString(siblingFile, "loot");

        HttpClientResponseException ex = assertThrows(
            HttpClientResponseException.class,
            () -> get("/api/brain/file?path=" + siblingFile)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    void fileNotFoundForMissingFileInsideGeminiDir() {
        Path missing = tempHome.resolve(".gemini").resolve("does-not-exist.txt");

        HttpClientResponseException ex = assertThrows(
            HttpClientResponseException.class,
            () -> get("/api/brain/file?path=" + missing)
        );
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void fileNotFoundForDirectory() throws IOException {
        Path dir = tempHome.resolve(".gemini").resolve("a-directory");
        Files.createDirectories(dir);

        HttpClientResponseException ex = assertThrows(
            HttpClientResponseException.class,
            () -> get("/api/brain/file?path=" + dir)
        );
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }
}
