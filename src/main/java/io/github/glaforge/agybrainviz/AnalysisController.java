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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;

@Controller("/api/analysis")
public class AnalysisController {

    private Path getBrainPath(String flavor) {
        if (flavor == null || flavor.isEmpty()) flavor = "antigravity-cli";
        return Paths.get(System.getProperty("user.home"), ".gemini", flavor, "brain");
    }

    private static final int MAX_TOKENS_PER_CHUNK = 100_000;
    private static final Map<String, ProgressState> progressMap = new ConcurrentHashMap<>();

    @Serdeable
    public record ProgressState(int progress, String phase) {}

    @Get(value = "/conversations/{id}/progress", produces = "application/json")
    public ProgressResponse getProgress(@PathVariable String id) {
        ProgressState state = progressMap.get(id);
        if (state == null) {
            return new ProgressResponse("", -1);
        }
        return new ProgressResponse(state.phase(), state.progress());
    }

    @Serdeable
    public record ProgressResponse(String phase, int progress) {}

    private final AnalyzerService analyzerService;
    private final ExecutorService executor;
    private final AiConfig aiConfig;
    private final TokenCounter tokenCounter;

    @Inject
    public AnalysisController(
        AnalyzerService analyzerService,
        @Named(TaskExecutors.IO) ExecutorService executor,
        AiConfig aiConfig,
        TokenCounter tokenCounter
    ) {
        this.analyzerService = analyzerService;
        this.executor = executor;
        this.aiConfig = aiConfig;
        this.tokenCounter = tokenCounter;
    }

    private static final Map<String, Object> runningTasks = new ConcurrentHashMap<>();

    @ExecuteOn(TaskExecutors.IO)
    @Get(value = "/conversations/{id}/summarize", produces = "application/json")
    public String summarizeConversation(
        @PathVariable String id,
        @QueryValue Optional<Boolean> force,
        @QueryValue Optional<String> flavor
    ) throws IOException {
        if (!aiConfig.isConfigured()) {
            // Serialize via Jackson so the message is always valid JSON, regardless of its content.
            return new ObjectMapper()
                .writeValueAsString(Map.of("summary", aiConfig.notConfiguredMessage()));
        }

        if (runningTasks.putIfAbsent(id, new Object()) != null) {
            return "{\"summary\": \"Analysis is already running in the background for this conversation. Please wait a moment and refresh.\"}";
        }

        try {
            Path brainPath = getBrainPath(flavor.orElse("antigravity-cli"));
            Path transcriptPath = brainPath
                .resolve(id)
                .resolve(".system_generated")
                .resolve("logs")
                .resolve("transcript.jsonl");
            if (!Files.exists(transcriptPath)) {
                return "{\"summary\": \"No transcript found.\"}";
            }

            boolean forceRecompute = force.orElse(false);
            Path summaryJsonPath = brainPath
                .resolve(id)
                .resolve(".system_generated")
                .resolve("logs")
                .resolve("summary.json");
            Path shortTitlePath = brainPath
                .resolve(id)
                .resolve(".system_generated")
                .resolve("logs")
                .resolve("short_title.txt");

            if (!forceRecompute && Files.exists(summaryJsonPath)) {
                String json = Files.readString(summaryJsonPath);
                return json;
            } else if (forceRecompute) {
                Files.deleteIfExists(summaryJsonPath);
            }

            ObjectMapper mapper = new ObjectMapper();
            AnalysisResponse responseObj = null;

            try {
                List<String> allLines = Files.readAllLines(transcriptPath);
                List<List<String>> sequences = TranscriptParser.parseSequences(allLines);

                ToIntFunction<String> tokenFn = tokenCounter::estimate;

                progressMap.put(id, new ProgressState(5, "Estimating Tokens & Chunking...")); // Phase 1: Estimating

                List<String> combinedLines = new ArrayList<>();
                for (List<String> seq : sequences) {
                    combinedLines.addAll(seq);
                }
                List<List<String>> optimalChunks = new ArrayList<>();
                TranscriptParser.splitIntoSafeChunks(
                    combinedLines,
                    tokenFn,
                    MAX_TOKENS_PER_CHUNK,
                    optimalChunks
                );

                System.out.println(
                    "Total optimal chunks to process in parallel: " + optimalChunks.size()
                );

                progressMap.put(id, new ProgressState(0, "Starting chunk processing...")); // start at 0%

                List<Future<AnalysisResponse>> futures = new ArrayList<>();
                AtomicInteger completed = new AtomicInteger(0);

                // Limit to 20 concurrent LLM requests, as we have drastically reduced chunk count
                Semaphore rateLimitSemaphore = new Semaphore(20);

                for (List<String> chunkLines : optimalChunks) {
                    futures.add(
                        executor.submit(() -> {
                            try {
                                rateLimitSemaphore.acquire();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return null;
                            }

                            try {
                                String chunk = String.join("\n", chunkLines);
                                AnalysisResponse seqResponse = null;
                                try {
                                    seqResponse = analyzerService.analyze(chunk);
                                } catch (Exception e) {
                                    System.err.println(
                                        "Failed to parse chunk from LLM: " + e.getMessage()
                                    );
                                    e.printStackTrace();
                                    // Ignore unparseable chunk
                                }

                                int comp = completed.incrementAndGet();
                                // Scale parallel processing to 90% of the total progress
                                int pct = (int) Math.round((comp * 90.0) / optimalChunks.size());
                                String phaseMsg =
                                    "Processing chunk " +
                                    comp +
                                    " of " +
                                    optimalChunks.size() +
                                    "...";

                                // Prevent progress moving backwards if futures finish out of order
                                progressMap.compute(
                                    id,
                                    (k, v) -> {
                                        if (v == null || pct > v.progress()) {
                                            return new ProgressState(pct, phaseMsg);
                                        }
                                        return v;
                                    }
                                );

                                return seqResponse;
                            } finally {
                                rateLimitSemaphore.release();
                            }
                        })
                    );
                }

                List<AnalysisResponse> seqResponses = new ArrayList<>();
                for (Future<AnalysisResponse> f : futures) {
                    AnalysisResponse r = f.get();
                    if (r != null) seqResponses.add(r);
                }

                if (seqResponses.isEmpty()) {
                    return "{\"summary\": \"No transcript lines found.\"}";
                } else if (seqResponses.size() == 1) {
                    responseObj = seqResponses.get(0);
                } else {
                    progressMap.put(id, new ProgressState(90, "Consolidating final analysis..."));

                    // Keep the progress bar moving smoothly during the long final LLM consolidation
                    AtomicBoolean consolidationDone = new AtomicBoolean(false);
                    Future<?> fakeProgress = executor.submit(() -> {
                        int p = 90;
                        while (!consolidationDone.get() && p < 99) {
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException e) {
                                break;
                            }
                            if (!consolidationDone.get()) {
                                int nextP = p + 1;
                                progressMap.put(
                                    id,
                                    new ProgressState(nextP, "Consolidating final analysis...")
                                );
                                p = nextP;
                            }
                        }
                    });

                    try {
                        responseObj =
                            recursivelyConsolidate(seqResponses, tokenFn, mapper, 500_000);
                    } finally {
                        consolidationDone.set(true);
                        fakeProgress.cancel(true);
                    }
                }

                progressMap.put(id, new ProgressState(100, "Done"));

                String jsonResponse = mapper.writeValueAsString(responseObj);

                // Try to extract shortTitle for shortTitlePath caching
                try {
                    String title = responseObj.shortTitle();
                    if (title != null && !title.isEmpty()) {
                        Files.writeString(shortTitlePath, title.trim());
                    }
                } catch (Exception e) {}

                try {
                    Files.writeString(summaryJsonPath, jsonResponse);
                    return jsonResponse;
                } catch (Exception e) {
                    throw new Exception("Invalid JSON response");
                }
            } catch (Exception e) {
                System.err.println("Exception caught during analysis:");
                e.printStackTrace();
                try {
                    return mapper.writeValueAsString(
                        Map.of("summary", "Error generating summary: " + e.getMessage())
                    );
                } catch (Exception ex) {
                    return "{\"summary\": \"Error generating summary: Unknown error\"}";
                }
            } finally {
                progressMap.remove(id);
            }
        } finally {
            runningTasks.remove(id);
        }
    }

    private AnalysisResponse recursivelyConsolidate(
        List<AnalysisResponse> responses,
        ToIntFunction<String> tokenFn,
        ObjectMapper mapper,
        int maxTokens
    ) throws Exception {
        String json = mapper.writeValueAsString(responses);
        try {
            int tokens = tokenFn.applyAsInt(json);
            if (tokens <= maxTokens) {
                return analyzerService.consolidateAnalysis(json);
            }
        } catch (Exception e) {
            // fallback
        }

        int mid = responses.size() / 2;
        AnalysisResponse r1 = recursivelyConsolidate(
            responses.subList(0, mid),
            tokenFn,
            mapper,
            maxTokens
        );
        AnalysisResponse r2 = recursivelyConsolidate(
            responses.subList(mid, responses.size()),
            tokenFn,
            mapper,
            maxTokens
        );
        return analyzerService.consolidateAnalysis(mapper.writeValueAsString(List.of(r1, r2)));
    }
}
