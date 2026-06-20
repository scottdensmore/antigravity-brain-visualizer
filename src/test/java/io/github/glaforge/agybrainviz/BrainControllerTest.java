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
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Integration tests for {@link BrainController}, covering the "browse sessions", "load transcript",
 * and "preview file" user journeys. A temporary {@code user.home} is used so the controller scans a
 * controlled fake {@code ~/.gemini} directory tree.
 *
 * <p>These tests mutate the process-global {@code user.home} system property, so they declare a
 * resource lock on it to stay correct if test parallelism is ever enabled.
 */
@MicronautTest
@ResourceLock("user.home")
class BrainControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    private static String originalUserHome;
    private static Path tempHome;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void setUpHome() throws IOException {
        originalUserHome = System.getProperty("user.home");
        tempHome = Files.createTempDirectory("agy-brain-test-home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterAll
    static void restoreHome() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    private Path brainDir(String flavor) throws IOException {
        Path dir = tempHome.resolve(".gemini").resolve(flavor).resolve("brain");
        Files.createDirectories(dir);
        return dir;
    }

    private void writeTranscript(String flavor, String id, String fileName, String content)
        throws IOException {
        Path logs = brainDir(flavor).resolve(id).resolve(".system_generated").resolve("logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve(fileName), content);
    }

    private String get(String uri) {
        return client.toBlocking().retrieve(uri);
    }

    private void writeCodexSession(String relPath, String content) throws IOException {
        Path file = tempHome.resolve(".codex").resolve("sessions").resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void writeClaudeCodeSession(String relPath, String content) throws IOException {
        Path file = tempHome.resolve(".claude").resolve("projects").resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // Codex and Claude Code sessions each share one tree (unlike the per-flavor Antigravity dirs),
    // so list-count assertions must start from a clean slate.
    private void resetCodexSessions() throws IOException {
        deleteTree(tempHome.resolve(".codex").resolve("sessions"));
    }

    private void resetClaudeCodeSessions() throws IOException {
        deleteTree(tempHome.resolve(".claude").resolve("projects"));
    }

    private void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {}
                });
        }
    }

    // ----- listConversations -----

    @Test
    void listReturnsEmptyWhenBrainDirMissing() {
        String body = get("/api/brain/conversations?flavor=does-not-exist-flavor");
        assertEquals("[]", body.trim());
    }

    @Test
    void listIncludesSessionsWithNonEmptyTranscriptAndDerivesSummaryFromUserRequest()
        throws IOException {
        String flavor = "list-flavor-1";
        writeTranscript(
            flavor,
            "abcdef1234567890",
            "transcript.jsonl",
            "{\"type\":\"USER_INPUT\",\"content\":\"<USER_REQUEST>\\nPlease fix the bug\\n</USER_REQUEST>\"}\n"
        );

        String body = get("/api/brain/conversations?flavor=" + flavor);
        JsonNode arr = MAPPER.readTree(body);
        assertEquals(1, arr.size());
        assertEquals("abcdef1234567890", arr.get(0).get("id").asText());
        assertEquals("Please fix the bug", arr.get(0).get("summary").asText());
    }

    @Test
    void listPrefersShortTitleFileForSummary() throws IOException {
        String flavor = "list-flavor-2";
        String id = "session-with-title";
        writeTranscript(
            flavor,
            id,
            "transcript.jsonl",
            "{\"type\":\"USER_INPUT\",\"content\":\"raw content\"}\n"
        );
        writeTranscript(flavor, id, "short_title.txt", "Curated Title\n");

        String body = get("/api/brain/conversations?flavor=" + flavor);
        JsonNode arr = MAPPER.readTree(body);
        assertEquals(1, arr.size());
        assertEquals("Curated Title", arr.get(0).get("summary").asText());
    }

    @Test
    void listExcludesDirectoriesWithoutOrWithEmptyTranscript() throws IOException {
        String flavor = "list-flavor-3";
        // A directory with no transcript at all.
        Files.createDirectories(brainDir(flavor).resolve("no-transcript-dir"));
        // A directory with an empty transcript file.
        writeTranscript(flavor, "empty-transcript-dir", "transcript.jsonl", "");
        // A valid one.
        writeTranscript(
            flavor,
            "valid-dir",
            "transcript.jsonl",
            "{\"type\":\"USER_INPUT\",\"content\":\"hi\"}\n"
        );

        String body = get("/api/brain/conversations?flavor=" + flavor);
        JsonNode arr = MAPPER.readTree(body);
        assertEquals(1, arr.size());
        assertEquals("valid-dir", arr.get(0).get("id").asText());
    }

    @Test
    void listSortsByMostRecentlyUpdatedFirst() throws IOException {
        String flavor = "list-flavor-4";
        writeTranscript(
            flavor,
            "older-session",
            "transcript.jsonl",
            "{\"type\":\"USER_INPUT\",\"content\":\"old\"}\n"
        );
        writeTranscript(
            flavor,
            "newer-session",
            "transcript.jsonl",
            "{\"type\":\"USER_INPUT\",\"content\":\"new\"}\n"
        );
        // Force a clearly newer modification time on the second transcript.
        Path newerTranscript = brainDir(flavor)
            .resolve("newer-session")
            .resolve(".system_generated")
            .resolve("logs")
            .resolve("transcript.jsonl");
        Files.setLastModifiedTime(
            newerTranscript,
            java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000)
        );

        String body = get("/api/brain/conversations?flavor=" + flavor);
        JsonNode arr = MAPPER.readTree(body);
        assertEquals(2, arr.size());
        assertEquals("newer-session", arr.get(0).get("id").asText());
        assertEquals("older-session", arr.get(1).get("id").asText());
    }

    // ----- getTranscript -----

    @Test
    void transcriptReturnsEmptyArrayWhenMissing() {
        String body = get("/api/brain/conversations/missing-id/transcript?flavor=t-flavor-x");
        assertEquals("[]", body.trim());
    }

    @Test
    void transcriptConvertsJsonlToJsonArray() throws IOException {
        String flavor = "t-flavor-1";
        writeTranscript(flavor, "conv-1", "transcript.jsonl", "{\"a\":1}\n{\"b\":2}\n");
        String body = get("/api/brain/conversations/conv-1/transcript?flavor=" + flavor);
        JsonNode arr = MAPPER.readTree(body);
        assertTrue(arr.isArray());
        assertEquals(2, arr.size());
        assertEquals(1, arr.get(0).get("a").asInt());
        assertEquals(2, arr.get(1).get("b").asInt());
    }

    @Test
    void transcriptPrefersFullTranscriptOverRegularOne() throws IOException {
        String flavor = "t-flavor-2";
        writeTranscript(flavor, "conv-2", "transcript.jsonl", "{\"which\":\"regular\"}\n");
        writeTranscript(flavor, "conv-2", "transcript_full.jsonl", "{\"which\":\"full\"}\n");

        String body = get("/api/brain/conversations/conv-2/transcript?flavor=" + flavor);
        JsonNode arr = MAPPER.readTree(body);
        assertEquals("full", arr.get(0).get("which").asText());
    }

    // ----- codex flavor -----

    @Test
    void listsCodexSessionsWithSummaryFromUserMessage() throws IOException {
        resetCodexSessions();
        writeCodexSession(
            "2026/06/20/rollout-2026-06-20T12-00-00-codexsession1.jsonl",
            "{\"type\":\"event_msg\",\"timestamp\":\"2026-06-20T12:00:00Z\",\"payload\":{\"type\":\"user_message\",\"message\":\"Refactor the parser\"}}\n"
        );

        String body = get("/api/brain/conversations?flavor=codex");
        JsonNode arr = MAPPER.readTree(body);
        assertEquals(1, arr.size());
        assertEquals("rollout-2026-06-20T12-00-00-codexsession1", arr.get(0).get("id").asText());
        assertEquals("Refactor the parser", arr.get(0).get("summary").asText());
    }

    @Test
    void codexTranscriptIsAdaptedToTheStepSchema() throws IOException {
        String id = "rollout-2026-06-20T13-00-00-codexsession2";
        writeCodexSession(
            "2026/06/20/" + id + ".jsonl",
            "{\"type\":\"response_item\",\"timestamp\":\"2026-06-20T13:00:00Z\",\"payload\":{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"hi\"}]}}\n" +
            "{\"type\":\"response_item\",\"timestamp\":\"2026-06-20T13:00:01Z\",\"payload\":{\"type\":\"function_call\",\"name\":\"exec_command\",\"arguments\":\"{\\\"cmd\\\":\\\"ls\\\"}\",\"call_id\":\"c1\"}}\n"
        );

        String body = get("/api/brain/conversations/" + id + "/transcript?flavor=codex");
        JsonNode arr = MAPPER.readTree(body);
        assertEquals(2, arr.size());
        assertEquals("USER_INPUT", arr.get(0).get("type").asText());
        assertEquals("exec_command", arr.get(1).get("tool_calls").get(0).get("name").asText());
    }

    @Test
    void codexTranscriptReturnsEmptyArrayForUnknownId() {
        String body = get("/api/brain/conversations/no-such-codex-id/transcript?flavor=codex");
        assertEquals("[]", body.trim());
    }

    @Test
    void codexSessionsSortNewestFirstAndFallBackToDefaultTitle() throws IOException {
        resetCodexSessions();
        // A malformed rollout (no parseable user message) falls back to a generated title.
        writeCodexSession("2026/06/19/rollout-older-codexolder.jsonl", "not valid json\n");
        writeCodexSession(
            "2026/06/20/rollout-newer-codexnewer.jsonl",
            "{\"type\":\"event_msg\",\"timestamp\":\"t\",\"payload\":{\"type\":\"user_message\",\"message\":\"newest\"}}\n"
        );
        Path newer = tempHome
            .resolve(".codex")
            .resolve("sessions")
            .resolve("2026/06/20/rollout-newer-codexnewer.jsonl");
        Files.setLastModifiedTime(
            newer,
            java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000)
        );

        JsonNode arr = MAPPER.readTree(get("/api/brain/conversations?flavor=codex"));
        assertEquals(2, arr.size());
        assertEquals("rollout-newer-codexnewer", arr.get(0).get("id").asText());
        assertEquals("newest", arr.get(0).get("summary").asText());
        // The malformed one still lists, with a generated fallback title.
        assertTrue(arr.get(1).get("summary").asText().startsWith("Codex session"));
    }

    // ----- claude-code flavor -----

    @Test
    void listsClaudeCodeSessionsWithSummaryFromUserPrompt() throws IOException {
        resetClaudeCodeSessions();
        writeClaudeCodeSession(
            "-Users-me-proj/11111111-2222-3333-4444-555555555555.jsonl",
            "{\"type\":\"user\",\"timestamp\":\"2026-06-07T00:00:00Z\",\"message\":{\"role\":\"user\",\"content\":\"Refactor the parser\"}}\n"
        );

        JsonNode arr = MAPPER.readTree(get("/api/brain/conversations?flavor=claude-code"));
        assertEquals(1, arr.size());
        assertEquals("11111111-2222-3333-4444-555555555555", arr.get(0).get("id").asText());
        assertEquals("Refactor the parser", arr.get(0).get("summary").asText());
    }

    @Test
    void claudeCodeTranscriptIsAdaptedToTheStepSchema() throws IOException {
        resetClaudeCodeSessions();
        String id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        writeClaudeCodeSession(
            "-Users-me-proj/" + id + ".jsonl",
            "{\"type\":\"user\",\"timestamp\":\"t\",\"message\":{\"role\":\"user\",\"content\":\"hi\"}}\n" +
            "{\"type\":\"assistant\",\"timestamp\":\"t\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"u1\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]}}\n"
        );

        JsonNode arr = MAPPER.readTree(
            get("/api/brain/conversations/" + id + "/transcript?flavor=claude-code")
        );
        assertEquals(2, arr.size());
        assertEquals("USER_INPUT", arr.get(0).get("type").asText());
        assertEquals("Bash", arr.get(1).get("tool_calls").get(0).get("name").asText());
    }

    @Test
    void claudeCodeTranscriptReturnsEmptyArrayForUnknownId() {
        String body = get("/api/brain/conversations/no-such-cc-id/transcript?flavor=claude-code");
        assertEquals("[]", body.trim());
    }

    // ----- getFileContent -----

    @Test
    void fileReturnsContentForPathInsideGeminiDir() throws IOException {
        Path geminiFile = tempHome.resolve(".gemini").resolve("notes.txt");
        Files.createDirectories(geminiFile.getParent());
        Files.writeString(geminiFile, "hello file");

        String body = get("/api/brain/file?path=" + geminiFile);
        assertEquals("hello file", body);
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
        // A ../ traversal that resolves out of ~/.gemini must be rejected after normalization.
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
        // ".gemini-evil" shares a string prefix with ".gemini" but is a distinct path component, so
        // the component-wise Path.startsWith check must still reject it.
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
