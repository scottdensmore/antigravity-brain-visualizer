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
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Integration tests for {@link AnalysisController}, covering the "generate AI analysis" journey. The
 * LLM ({@link AnalyzerService}), the API-key accessor ({@link GeminiConfig}) and the token estimator
 * ({@link TokenCounter}) are replaced with deterministic mock beans so the orchestration, caching,
 * and error paths are exercised without any network access.
 *
 * <p>These tests mutate the process-global {@code user.home} system property, so they declare a
 * resource lock on it to stay correct if test parallelism is ever enabled.
 */
@MicronautTest
@ResourceLock("user.home")
class AnalysisControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Controllable mock state, shared with the @MockBean factory methods below.
    private static final AtomicReference<Optional<String>> API_KEY = new AtomicReference<>(
        Optional.of("test-key")
    );
    private static final AtomicInteger TOKEN_RESULT = new AtomicInteger(10);
    private static final List<String> ANALYZE_CALLS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger CONSOLIDATE_CALLS = new AtomicInteger(0);

    private static String originalUserHome;
    private static Path tempHome;

    @BeforeAll
    static void setUpHome() throws IOException {
        originalUserHome = System.getProperty("user.home");
        tempHome = Files.createTempDirectory("agy-analysis-test-home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterAll
    static void restoreHome() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @BeforeEach
    void resetMocks() {
        API_KEY.set(Optional.of("test-key"));
        TOKEN_RESULT.set(10);
        ANALYZE_CALLS.clear();
        CONSOLIDATE_CALLS.set(0);
    }

    @MockBean(GeminiConfig.class)
    GeminiConfig geminiConfig() {
        return new GeminiConfig() {
            @Override
            public Optional<String> apiKey() {
                return API_KEY.get();
            }
        };
    }

    @MockBean(TokenCounter.class)
    TokenCounter tokenCounter() {
        return new TokenCounter(new GeminiConfig()) {
            @Override
            public int estimate(String text) {
                return TOKEN_RESULT.get();
            }
        };
    }

    @MockBean(AnalyzerService.class)
    AnalyzerService analyzerService() {
        return new AnalyzerService() {
            @Override
            public AnalysisResponse analyze(String transcript) {
                ANALYZE_CALLS.add(transcript);
                return new AnalysisResponse(
                    "Chunk Title",
                    List.of("chunk flow"),
                    List.of(),
                    List.of(),
                    List.of(),
                    "chunk summary"
                );
            }

            @Override
            public AnalysisResponse refineAnalysis(String previousAnalysis, String transcript) {
                return analyze(transcript);
            }

            @Override
            public AnalysisResponse consolidateAnalysis(String combinedSummariesJson) {
                CONSOLIDATE_CALLS.incrementAndGet();
                return new AnalysisResponse(
                    "Final Title",
                    List.of("final flow"),
                    List.of(),
                    List.of(),
                    List.of(),
                    "final summary"
                );
            }
        };
    }

    private void writeTranscript(String id, String content) throws IOException {
        Path logs = tempHome
            .resolve(".gemini")
            .resolve("antigravity-cli")
            .resolve("brain")
            .resolve(id)
            .resolve(".system_generated")
            .resolve("logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("transcript.jsonl"), content);
    }

    private Path logsDir(String id) {
        return tempHome
            .resolve(".gemini")
            .resolve("antigravity-cli")
            .resolve("brain")
            .resolve(id)
            .resolve(".system_generated")
            .resolve("logs");
    }

    private String get(String uri) {
        return client.toBlocking().retrieve(uri);
    }

    // ----- progress endpoint -----

    @Test
    void progressReturnsSentinelWhenNoAnalysisRunning() throws IOException {
        String body = get("/api/analysis/conversations/unknown-id/progress");
        JsonNode node = MAPPER.readTree(body);
        assertEquals("", node.get("phase").asText());
        assertEquals(-1, node.get("progress").asInt());
    }

    // ----- summarize: guard paths -----

    @Test
    void summarizeReturnsErrorWhenApiKeyMissing() throws IOException {
        API_KEY.set(Optional.empty());
        String body = get("/api/analysis/conversations/any-id/summarize");
        JsonNode node = MAPPER.readTree(body);
        assertTrue(node.get("summary").asText().contains("GEMINI_API_KEY"));
    }

    @Test
    void summarizeReturnsNoTranscriptMessageWhenTranscriptMissing() throws IOException {
        String body = get("/api/analysis/conversations/no-transcript-here/summarize");
        JsonNode node = MAPPER.readTree(body);
        assertEquals("No transcript found.", node.get("summary").asText());
    }

    @Test
    void summarizeReturnsCachedSummaryWithoutCallingLlm() throws IOException {
        String id = "cached-session";
        writeTranscript(id, "{\"type\":\"USER_INPUT\",\"content\":\"hi\"}\n");
        Files.writeString(
            logsDir(id).resolve("summary.json"),
            "{\"summary\":\"previously cached\"}"
        );

        String body = get("/api/analysis/conversations/" + id + "/summarize");
        JsonNode node = MAPPER.readTree(body);
        assertEquals("previously cached", node.get("summary").asText());
        assertTrue(ANALYZE_CALLS.isEmpty(), "cached path must not invoke the LLM");
    }

    // ----- summarize: full pipeline -----

    @Test
    void summarizeSingleChunkRunsAnalysisAndCachesResult() throws IOException {
        String id = "single-chunk-session";
        writeTranscript(id, "{\"type\":\"USER_INPUT\",\"content\":\"do the thing\"}\n");

        String body = get("/api/analysis/conversations/" + id + "/summarize?force=true");
        JsonNode node = MAPPER.readTree(body);

        assertEquals("chunk summary", node.get("summary").asText());
        assertEquals("Chunk Title", node.get("shortTitle").asText());
        assertEquals(1, ANALYZE_CALLS.size());
        assertEquals(0, CONSOLIDATE_CALLS.get());
        // Result is cached to disk for next time.
        assertTrue(Files.exists(logsDir(id).resolve("summary.json")));
        assertEquals("Chunk Title", Files.readString(logsDir(id).resolve("short_title.txt")));
    }

    @Test
    void summarizeMultipleChunksConsolidatesResults() throws IOException {
        String id = "multi-chunk-session";
        // One user line + two tool calls => three condensed lines.
        writeTranscript(
            id,
            "{\"type\":\"USER_INPUT\",\"content\":\"go\"}\n" +
            "{\"type\":\"PLANNER_RESPONSE\",\"tool_calls\":[" +
            "{\"name\":\"a\",\"arguments\":{\"CommandLine\":\"one\"}}," +
            "{\"name\":\"b\",\"arguments\":{\"CommandLine\":\"two\"}}]}\n"
        );
        // Force every multi-line join over budget so chunking splits down to single lines.
        TOKEN_RESULT.set(100_001);

        String body = get("/api/analysis/conversations/" + id + "/summarize?force=true");
        JsonNode node = MAPPER.readTree(body);

        assertEquals("final summary", node.get("summary").asText());
        assertEquals("Final Title", node.get("shortTitle").asText());
        assertEquals(3, ANALYZE_CALLS.size());
        assertTrue(CONSOLIDATE_CALLS.get() >= 1);
    }
}
