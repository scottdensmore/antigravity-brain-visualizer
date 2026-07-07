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
    private final List<SessionSource> sessionSources;

    @Inject
    public AnalysisController(
        AnalyzerService analyzerService,
        @Named(TaskExecutors.IO) ExecutorService executor,
        AiConfig aiConfig,
        TokenCounter tokenCounter,
        List<SessionSource> sessionSources
    ) {
        this.analyzerService = analyzerService;
        this.executor = executor;
        this.aiConfig = aiConfig;
        this.tokenCounter = tokenCounter;
        this.sessionSources = sessionSources;
    }

    private Optional<SessionSource> sourceFor(String flavor) {
        return sessionSources.stream().filter(s -> s.handles(flavor)).findFirst();
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
            Optional<SessionSource> source = sourceFor(flavor.orElse(""));
            boolean external = source.isPresent();
            boolean forceRecompute = force.orElse(false);

            // Antigravity caches the summary inside the agent's own brain dir; external sources
            // (Codex, Claude Code) cache via their SessionSource. Antigravity paths are resolved up
            // front; for external sources they stay null.
            Path logsDir = external
                ? null
                : AntigravityPaths.logsDir(flavor.orElse(AntigravityPaths.DEFAULT_FLAVOR), id);
            Path transcriptPath = external ? null : AntigravityPaths.transcript(logsDir);
            Path summaryJsonPath = external ? null : AntigravityPaths.summaryJson(logsDir);
            Path shortTitlePath = external ? null : AntigravityPaths.shortTitle(logsDir);

            boolean exists = external
                ? source.get().sessionExists(id)
                : Files.exists(transcriptPath);
            if (!exists) {
                return "{\"summary\": \"No transcript found.\"}";
            }

            if (!forceRecompute) {
                Optional<String> cached = external
                    ? source.get().cachedSummary(id)
                    : (
                        Files.exists(summaryJsonPath)
                            ? Optional.of(Files.readString(summaryJsonPath))
                            : Optional.empty()
                    );
                if (cached.isPresent()) {
                    return cached.get();
                }
            } else if (external) {
                source.get().deleteCache(id);
            } else {
                Files.deleteIfExists(summaryJsonPath);
            }

            ObjectMapper mapper = new ObjectMapper();
            AnalysisResponse responseObj = null;
            boolean consolidationFellBack = false;

            try {
                List<List<String>> sequences = external
                    ? source.get().analysisSequences(id)
                    : TranscriptParser.parseSequences(Files.readAllLines(transcriptPath));

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
                    // Distinguish "nothing to analyze" from "the model failed for every chunk".
                    String msg = optimalChunks.isEmpty()
                        ? "No transcript lines found."
                        : "Analysis could not be generated: the model did not return a result for any part of this conversation. Please try again.";
                    return mapper.writeValueAsString(Map.of("summary", msg));
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
                    } catch (Exception e) {
                        // LLM consolidation failed (e.g. API timeout). Rather than discard all the
                        // per-chunk work, merge the partial analyses locally so the user still gets
                        // a usable summary.
                        System.err.println(
                            "Consolidation failed; using local merge fallback: " + e.getMessage()
                        );
                        responseObj = localMerge(seqResponses);
                        consolidationFellBack = true;
                    } finally {
                        consolidationDone.set(true);
                        fakeProgress.cancel(true);
                    }
                }

                progressMap.put(id, new ProgressState(100, "Done"));

                String jsonResponse = mapper.writeValueAsString(responseObj);

                // Don't persist a degraded local-merge fallback: the consolidation failure was
                // likely transient, so a later (non-forced) load should retry the LLM rather than
                // serve the cruder summary.
                if (consolidationFellBack) {
                    return jsonResponse;
                }

                String title = responseObj.shortTitle();
                try {
                    if (external) {
                        source.get().writeCache(id, jsonResponse, title);
                    } else {
                        // The short title is best-effort; a failure here must not block caching or
                        // returning the summary.
                        try {
                            if (title != null && !title.isEmpty()) {
                                Files.writeString(shortTitlePath, title.trim());
                            }
                        } catch (Exception ignore) {}
                        Files.writeString(summaryJsonPath, jsonResponse);
                    }
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
        boolean withinBudget;
        try {
            withinBudget = tokenFn.applyAsInt(json) <= maxTokens;
        } catch (Exception e) {
            // Only token estimation is best-effort here; fall back to a char-length heuristic so a
            // failed estimate doesn't get mistaken for a failed consolidation.
            withinBudget = (json.length() / 4) <= maxTokens;
        }
        // A consolidation failure below propagates to the caller (which falls back to a local merge).
        if (withinBudget || responses.size() <= 1) {
            return analyzerService.consolidateAnalysis(json);
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

    /**
     * Deterministically merges per-chunk analyses without calling the LLM. Used as a fallback when
     * LLM consolidation fails, so the user still gets a usable (if less polished) summary instead of
     * an error.
     */
    private AnalysisResponse localMerge(List<AnalysisResponse> responses) {
        String shortTitle = "Session analysis";
        StringBuilder summary = new StringBuilder();
        List<String> flow = new ArrayList<>();
        List<AgentAction> actions = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        for (AnalysisResponse r : responses) {
            if (r == null) continue;
            if (
                "Session analysis".equals(shortTitle) &&
                r.shortTitle() != null &&
                !r.shortTitle().isBlank()
            ) {
                shortTitle = r.shortTitle();
            }
            if (r.summary() != null && !r.summary().isBlank()) {
                if (summary.length() > 0) summary.append(" ");
                summary.append(r.summary().trim());
            }
            if (r.flow() != null) {
                for (String f : r.flow()) if (f != null && !flow.contains(f)) flow.add(f);
            }
            if (r.agentActions() != null) actions.addAll(r.agentActions());
            if (r.issues() != null) issues.addAll(r.issues());
            if (r.recommendations() != null) {
                for (String rec : r.recommendations()) {
                    if (rec != null && !recommendations.contains(rec)) recommendations.add(rec);
                }
            }
        }

        String summaryText = summary.length() > 4000
            ? summary.substring(0, 4000) + "..."
            : summary.toString();
        summaryText =
            "(Combined from " +
            responses.size() +
            " partial analyses; full consolidation was unavailable.) " +
            summaryText;

        return new AnalysisResponse(
            shortTitle,
            cap(flow, 40),
            cap(actions, 40),
            cap(issues, 40),
            cap(recommendations, 30),
            summaryText
        );
    }

    private static <T> List<T> cap(List<T> list, int max) {
        return list.size() > max ? new ArrayList<>(list.subList(0, max)) : list;
    }
}
