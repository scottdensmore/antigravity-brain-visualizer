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
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Integration tests for {@link AnalysisController}, covering the "generate AI analysis" journey.
 * Sessions and cached summaries come from the store; the LLM, provider config, and token estimator
 * are deterministic mock beans, so the orchestration, caching, and error paths run without a network.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // required by TestPropertyProvider
class AnalysisControllerTest implements TestPropertyProvider {

    @Override
    public Map<String, String> getProperties() {
        return TestPostgres.datasourceProperties();
    }

    @Inject
    @Client("/")
    HttpClient client;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final AtomicReference<String> PROVIDER = new AtomicReference<>("gemini");
    private static final AtomicReference<Optional<String>> API_KEY = new AtomicReference<>(
        Optional.of("test-key")
    );
    private static final AtomicInteger TOKEN_RESULT = new AtomicInteger(10);
    private static final List<String> ANALYZE_CALLS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger CONSOLIDATE_CALLS = new AtomicInteger(0);

    private static final AtomicReference<CountDownLatch> ANALYZE_STARTED = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> ANALYZE_RELEASE = new AtomicReference<>();

    private static final AtomicBoolean ANALYZE_FAILS = new AtomicBoolean(false);
    private static final AtomicBoolean CONSOLIDATE_FAILS = new AtomicBoolean(false);

    // Normalized-schema steps (USER_INPUT + FUNCTION_CALL) that yield analysis sequences for
    // Codex/Claude-style sources.
    private static final String NORMALIZED_STEPS =
        "[{\"type\":\"USER_INPUT\",\"content\":\"do it\"}," +
        "{\"type\":\"FUNCTION_CALL\",\"source\":\"MODEL\",\"tool_calls\":[{\"name\":\"exec_command\",\"args\":{\"command\":\"ls\"}}]}]";

    @BeforeEach
    void reset() throws SQLException {
        PROVIDER.set("gemini");
        API_KEY.set(Optional.of("test-key"));
        TOKEN_RESULT.set(10);
        ANALYZE_CALLS.clear();
        CONSOLIDATE_CALLS.set(0);
        ANALYZE_STARTED.set(null);
        ANALYZE_RELEASE.set(null);
        ANALYZE_FAILS.set(false);
        CONSOLIDATE_FAILS.set(false);
        PostgresTest.resetStore();
    }

    @MockBean(AiConfig.class)
    AiConfig aiConfig() {
        return new AiConfig("gemini", "", "", "", "") {
            @Override
            public Provider provider() {
                return "ollama".equalsIgnoreCase(PROVIDER.get())
                    ? Provider.OLLAMA
                    : Provider.GEMINI;
            }

            @Override
            public Optional<String> geminiApiKey() {
                return API_KEY.get();
            }
        };
    }

    @MockBean(TokenCounter.class)
    TokenCounter tokenCounter() {
        return new TokenCounter(new AiConfig("gemini", "", "", "", "")) {
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
                if (ANALYZE_FAILS.get()) throw new RuntimeException("analyze failed");
                CountDownLatch started = ANALYZE_STARTED.get();
                if (started != null) {
                    started.countDown();
                    try {
                        ANALYZE_RELEASE.get().await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
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
                if (CONSOLIDATE_FAILS.get()) throw new RuntimeException("consolidation failed");
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

    private String get(String uri) {
        return client.toBlocking().retrieve(uri);
    }

    private void seed(String source, String id, String stepsJson) {
        PostgresTest.seedSession(source, id, "t-" + id, stepsJson, null, 1L);
    }

    private void seedSummary(String source, String id, String summaryJson) {
        new SummaryRepository(TestPostgres.dataSource()).upsert(source, id, summaryJson, "t");
    }

    private Optional<String> cached(String source, String id) {
        return new SummaryRepository(TestPostgres.dataSource()).find(source, id);
    }

    // A native-Antigravity transcript: one user line plus a planner response carrying two tool calls,
    // so TranscriptParser yields three condensed lines.
    private static final String ANTIGRAVITY_THREE_LINES =
        "[{\"type\":\"USER_INPUT\",\"content\":\"go\"}," +
        "{\"type\":\"PLANNER_RESPONSE\",\"tool_calls\":[" +
        "{\"name\":\"a\",\"arguments\":{\"CommandLine\":\"one\"}}," +
        "{\"name\":\"b\",\"arguments\":{\"CommandLine\":\"two\"}}]}]";

    private static final String ANTIGRAVITY_ONE_LINE =
        "[{\"type\":\"USER_INPUT\",\"content\":\"do the thing\"}]";

    // ----- progress endpoint -----

    @Test
    void progressReturnsSentinelWhenNoAnalysisRunning() throws IOException {
        JsonNode node = MAPPER.readTree(get("/api/analysis/conversations/unknown-id/progress"));
        assertEquals("", node.get("phase").asText());
        assertEquals(-1, node.get("progress").asInt());
    }

    // ----- summarize: guard paths -----

    @Test
    void summarizeReturnsErrorWhenApiKeyMissing() throws IOException {
        API_KEY.set(Optional.empty());
        JsonNode node = MAPPER.readTree(get("/api/analysis/conversations/any-id/summarize"));
        assertTrue(node.get("summary").asText().contains("GEMINI_API_KEY"));
    }

    @Test
    void summarizeRunsWithoutAnApiKeyWhenUsingOllama() throws IOException {
        PROVIDER.set("ollama");
        API_KEY.set(Optional.empty());
        String id = "ollama-session";
        seed("antigravity-cli", id, ANTIGRAVITY_ONE_LINE);
        seedSummary("antigravity-cli", id, "{\"summary\":\"local result\"}");

        JsonNode node = MAPPER.readTree(get("/api/analysis/conversations/" + id + "/summarize"));
        assertEquals("local result", node.get("summary").asText());
    }

    @Test
    void summarizeReturnsNoTranscriptMessageWhenTranscriptMissing() throws IOException {
        JsonNode node = MAPPER.readTree(
            get("/api/analysis/conversations/no-transcript-here/summarize")
        );
        assertEquals("No transcript found.", node.get("summary").asText());
    }

    // ----- codex / claude source analysis (normalized schema) -----

    @Test
    void summarizesCodexSessionAndCachesResult() throws IOException {
        String id = "rollout-2026-06-20T15-00-00-codexanalysis";
        seed("codex", id, NORMALIZED_STEPS);

        String body = get(
            "/api/analysis/conversations/" + id + "/summarize?flavor=codex&force=true"
        );
        assertEquals("chunk summary", MAPPER.readTree(body).get("summary").asText());
        assertEquals(1, ANALYZE_CALLS.size());

        String again = get("/api/analysis/conversations/" + id + "/summarize?flavor=codex");
        assertEquals("Chunk Title", MAPPER.readTree(again).get("shortTitle").asText());
        assertEquals(1, ANALYZE_CALLS.size());
    }

    @Test
    void codexSummarizeReturnsNoTranscriptForUnknownId() throws IOException {
        JsonNode node = MAPPER.readTree(
            get("/api/analysis/conversations/unknown-codex/summarize?flavor=codex")
        );
        assertEquals("No transcript found.", node.get("summary").asText());
    }

    @Test
    void summarizesClaudeCodeSessionAndCachesResult() throws IOException {
        String id = "12121212-3434-5656-7878-909090909090";
        seed("claude-code", id, NORMALIZED_STEPS);

        String body = get(
            "/api/analysis/conversations/" + id + "/summarize?flavor=claude-code&force=true"
        );
        assertEquals("chunk summary", MAPPER.readTree(body).get("summary").asText());
        assertEquals(1, ANALYZE_CALLS.size());

        String again = get("/api/analysis/conversations/" + id + "/summarize?flavor=claude-code");
        assertEquals("Chunk Title", MAPPER.readTree(again).get("shortTitle").asText());
        assertEquals(1, ANALYZE_CALLS.size());
    }

    @Test
    void summarizeReturnsCachedSummaryWithoutCallingLlm() throws IOException {
        String id = "cached-session";
        seed("antigravity-cli", id, ANTIGRAVITY_ONE_LINE);
        seedSummary("antigravity-cli", id, "{\"summary\":\"previously cached\"}");

        JsonNode node = MAPPER.readTree(get("/api/analysis/conversations/" + id + "/summarize"));
        assertEquals("previously cached", node.get("summary").asText());
        assertTrue(ANALYZE_CALLS.isEmpty(), "cached path must not invoke the LLM");
    }

    // ----- graceful failure -----

    @Test
    void fallsBackToLocalMergeWhenConsolidationFails() throws IOException {
        String id = "consolidation-fallback";
        seed("antigravity-cli", id, ANTIGRAVITY_THREE_LINES);
        TOKEN_RESULT.set(100_001); // force multiple chunks -> consolidation
        CONSOLIDATE_FAILS.set(true);

        JsonNode node = MAPPER.readTree(
            get("/api/analysis/conversations/" + id + "/summarize?force=true")
        );
        String summary = node.get("summary").asText();

        assertFalse(summary.startsWith("Error generating summary"));
        assertTrue(summary.contains("partial analyses"));
        assertTrue(summary.contains("chunk summary"));
        assertEquals("Chunk Title", node.get("shortTitle").asText());
        // A degraded fallback must NOT be cached, so a later load retries the real consolidation.
        assertTrue(cached("antigravity-cli", id).isEmpty());
    }

    @Test
    void returnsClearMessageWhenTheModelFailsForEveryChunk() throws IOException {
        String id = "all-chunks-fail";
        seed("antigravity-cli", id, ANTIGRAVITY_ONE_LINE);
        ANALYZE_FAILS.set(true);

        String summary = MAPPER
            .readTree(get("/api/analysis/conversations/" + id + "/summarize?force=true"))
            .get("summary")
            .asText();
        assertFalse(summary.startsWith("Error generating summary"));
        assertTrue(summary.contains("could not be generated"));
    }

    // ----- summarize: full pipeline -----

    @Test
    void summarizeSingleChunkRunsAnalysisAndCachesResult() throws IOException {
        String id = "single-chunk-session";
        seed("antigravity-cli", id, ANTIGRAVITY_ONE_LINE);

        JsonNode node = MAPPER.readTree(
            get("/api/analysis/conversations/" + id + "/summarize?force=true")
        );

        assertEquals("chunk summary", node.get("summary").asText());
        assertEquals("Chunk Title", node.get("shortTitle").asText());
        assertEquals(1, ANALYZE_CALLS.size());
        assertEquals(0, CONSOLIDATE_CALLS.get());
        // Result is cached in the store for next time.
        assertTrue(cached("antigravity-cli", id).orElse("").contains("Chunk Title"));
    }

    @Test
    void summarizeMultipleChunksConsolidatesResults() throws IOException {
        String id = "multi-chunk-session";
        seed("antigravity-cli", id, ANTIGRAVITY_THREE_LINES);
        // Force every multi-line join over budget so chunking splits down to single lines.
        TOKEN_RESULT.set(100_001);

        JsonNode node = MAPPER.readTree(
            get("/api/analysis/conversations/" + id + "/summarize?force=true")
        );

        assertEquals("final summary", node.get("summary").asText());
        assertEquals("Final Title", node.get("shortTitle").asText());
        assertEquals(3, ANALYZE_CALLS.size());
        assertTrue(CONSOLIDATE_CALLS.get() >= 1);
    }

    @Test
    void forceRecomputeOverwritesAnExistingCachedSummary() throws IOException {
        String id = "force-session";
        seed("antigravity-cli", id, ANTIGRAVITY_ONE_LINE);
        seedSummary("antigravity-cli", id, "{\"summary\":\"stale\"}");

        JsonNode node = MAPPER.readTree(
            get("/api/analysis/conversations/" + id + "/summarize?force=true")
        );

        assertEquals("chunk summary", node.get("summary").asText());
        assertEquals(1, ANALYZE_CALLS.size());
        assertTrue(cached("antigravity-cli", id).orElse("").contains("chunk summary"));
    }

    @Test
    void summarizeReportsAlreadyRunningForAConcurrentRequest() throws Exception {
        String id = "concurrent-session";
        seed("antigravity-cli", id, ANTIGRAVITY_ONE_LINE);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ANALYZE_STARTED.set(started);
        ANALYZE_RELEASE.set(release);

        ExecutorService background = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = background.submit(() ->
                get("/api/analysis/conversations/" + id + "/summarize?force=true")
            );
            assertTrue(started.await(5, TimeUnit.SECONDS), "first analysis did not start");

            String second = get("/api/analysis/conversations/" + id + "/summarize?force=true");
            assertTrue(
                second.contains("already running"),
                "concurrent request should be rejected, got: " + second
            );

            release.countDown();
            String firstResult = first.get(10, TimeUnit.SECONDS);
            assertTrue(firstResult.contains("chunk summary"));
        } finally {
            release.countDown();
            background.shutdownNow();
        }
    }
}
