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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.google.genai.GoogleGenAiTokenCountEstimator;
import dev.langchain4j.service.AiServices;
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
import java.util.concurrent.atomic.AtomicInteger;

@Controller("/api/analysis")
public class AnalysisController {

    private Path getBrainPath(String flavor) {
        if (flavor == null || flavor.isEmpty()) flavor = "antigravity-cli";
        return Paths.get(System.getProperty("user.home"), ".gemini", flavor, "brain");
    }

    private static final int MAX_TOKENS_PER_CHUNK = 100_000;
    private static final Map<String, Integer> progressMap = new ConcurrentHashMap<>();

    @Get(value = "/conversations/{id}/progress", produces = "application/json")
    public ProgressResponse getProgress(@PathVariable String id) {
        int progress = progressMap.getOrDefault(id, -1);
        if (progress == -2) {
            return new ProgressResponse("Estimating Tokens & Chunking...", 5);
        } else if (progress >= 0) {
            return new ProgressResponse("Analyzing chunks...", progress);
        }
        return new ProgressResponse("", -1);
    }

    @Serdeable
    public record ProgressResponse(String phase, int progress) {}

    private final AnalyzerService analyzerService;
    private final ExecutorService executor;

    @Inject
    public AnalysisController(
        ChatModel chatModel,
        @Named(TaskExecutors.IO) ExecutorService executor
    ) {
        this.analyzerService =
            AiServices.builder(AnalyzerService.class).chatModel(chatModel).build();
        this.executor = executor;
    }

    private void splitIntoSafeChunks(
        List<String> lines,
        TokenCountEstimator estimator,
        int maxTokens,
        List<List<String>> safeChunks
    ) {
        if (lines.isEmpty()) return;
        String text = String.join("\n", lines);
        try {
            int tokens = estimator.estimateTokenCountInText(text);
            if (tokens <= maxTokens || lines.size() == 1) {
                safeChunks.add(lines);
            } else {
                int mid = lines.size() / 2;
                splitIntoSafeChunks(lines.subList(0, mid), estimator, maxTokens, safeChunks);
                splitIntoSafeChunks(
                    lines.subList(mid, lines.size()),
                    estimator,
                    maxTokens,
                    safeChunks
                );
            }
        } catch (Exception e) {
            int fallbackTokens = text.length() / 2;
            if (fallbackTokens <= maxTokens || lines.size() == 1) {
                safeChunks.add(lines);
            } else {
                int mid = lines.size() / 2;
                splitIntoSafeChunks(lines.subList(0, mid), estimator, maxTokens, safeChunks);
                splitIntoSafeChunks(
                    lines.subList(mid, lines.size()),
                    estimator,
                    maxTokens,
                    safeChunks
                );
            }
        }
    }

    @ExecuteOn(TaskExecutors.IO)
    @Get(value = "/conversations/{id}/summarize", produces = "application/json")
    public String summarizeConversation(
        @PathVariable String id,
        @QueryValue Optional<Boolean> force,
        @QueryValue Optional<String> flavor
    ) throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            return "{\"summary\": \"Error: GEMINI_API_KEY environment variable is not set. Cannot use LangChain4j analysis.\"}";
        }

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
        }

        ObjectMapper mapper = new ObjectMapper();
        AnalysisResponse responseObj = null;

        try {
            List<String> allLines = Files.readAllLines(transcriptPath);
            List<List<String>> sequences = new ArrayList<>();
            List<String> currentSequence = new ArrayList<>();

            for (String line : allLines) {
                if (line.trim().isEmpty()) continue;
                try {
                    JsonNode node = mapper.readTree(line);
                    String type = node.path("type").asText("");
                    if (
                        "USER_INPUT".equals(type) ||
                        "USER_EXPLICIT".equals(node.path("source").asText(""))
                    ) {
                        if (!currentSequence.isEmpty()) {
                            sequences.add(deduplicateSequence(currentSequence));
                            currentSequence = new ArrayList<>();
                        }
                        String content = node.path("content").asText("");
                        currentSequence.add(
                            "USER REQUEST: " +
                            content.substring(0, Math.min(2000, content.length()))
                        );
                    } else if (
                        "PLANNER_RESPONSE".equals(type) ||
                        "MODEL".equals(node.path("source").asText(""))
                    ) {
                        JsonNode tools = node.path("tool_calls");
                        if (!tools.isMissingNode() && tools.isArray()) {
                            for (JsonNode tool : tools) {
                                String name = tool.path("name").asText("unknown");
                                String action = tool
                                    .path("arguments")
                                    .path("toolAction")
                                    .asText("");
                                String tgt = tool.path("arguments").path("TargetFile").asText("");
                                if (tgt.isEmpty()) tgt =
                                    tool.path("arguments").path("CommandLine").asText("");
                                currentSequence.add(
                                    "AGENT ACTION: [" + name + "] " + action + " -> " + tgt
                                );
                            }
                        }
                    } else if (
                        node.has("error") ||
                        (
                            node.has("content") &&
                            node.path("content").asText("").contains("Exception")
                        )
                    ) {
                        String err = node.path("content").asText("");
                        currentSequence.add(
                            "SYSTEM EVENT/ERROR: " + err.substring(0, Math.min(500, err.length()))
                        );
                    }
                } catch (Exception e) {
                    // skip malformed
                }
            }
            if (!currentSequence.isEmpty()) {
                sequences.add(deduplicateSequence(currentSequence));
            }

            TokenCountEstimator estimator = GoogleGenAiTokenCountEstimator
                .builder()
                .apiKey(apiKey)
                .modelName("gemini-3.5-flash")
                .build();

            progressMap.put(id, -2); // Phase 1: Estimating

            System.out.println("Total sequences to process in parallel: " + sequences.size());

            progressMap.put(id, 0); // start at 0%

            List<Future<AnalysisResponse>> futures = new ArrayList<>();
            AtomicInteger completed = new AtomicInteger(0);

            for (List<String> seq : sequences) {
                futures.add(
                    executor.submit(() -> {
                        List<List<String>> safeChunks = new ArrayList<>();
                        splitIntoSafeChunks(seq, estimator, MAX_TOKENS_PER_CHUNK, safeChunks);

                        AnalysisResponse seqResponse = null;
                        for (List<String> linesChunk : safeChunks) {
                            String chunk = String.join("\n", linesChunk);
                            if (seqResponse == null) {
                                try {
                                    seqResponse = analyzerService.analyze(chunk);
                                } catch (Exception e) {
                                    // Ignore unparseable chunk
                                }
                            } else {
                                try {
                                    String prevJson = mapper.writeValueAsString(seqResponse);
                                    seqResponse = analyzerService.refineAnalysis(prevJson, chunk);
                                } catch (Exception e) {
                                    // Fallback
                                }
                            }
                        }

                        int comp = completed.incrementAndGet();
                        int pct = (int) Math.round((comp * 100.0) / sequences.size());
                        if (pct == 100) pct = 99; // Reserve 100 for consolidation
                        final int finalPct = pct;
                        progressMap.compute(
                            id,
                            (k, v) -> (v == null || finalPct > v) ? finalPct : v
                        );

                        return seqResponse;
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
                responseObj = recursivelyConsolidate(seqResponses, estimator, mapper, 500_000);
            }

            progressMap.put(id, 100);

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
    }

    private AnalysisResponse recursivelyConsolidate(
        List<AnalysisResponse> responses,
        TokenCountEstimator estimator,
        ObjectMapper mapper,
        int maxTokens
    ) throws Exception {
        String json = mapper.writeValueAsString(responses);
        try {
            int tokens = estimator.estimateTokenCountInText(json);
            if (tokens <= maxTokens) {
                return analyzerService.consolidateAnalysis(json);
            }
        } catch (Exception e) {
            // fallback
        }

        int mid = responses.size() / 2;
        AnalysisResponse r1 = recursivelyConsolidate(
            responses.subList(0, mid),
            estimator,
            mapper,
            maxTokens
        );
        AnalysisResponse r2 = recursivelyConsolidate(
            responses.subList(mid, responses.size()),
            estimator,
            mapper,
            maxTokens
        );
        return analyzerService.consolidateAnalysis(mapper.writeValueAsString(List.of(r1, r2)));
    }

    private List<String> deduplicateSequence(List<String> sequence) {
        if (sequence.isEmpty()) return sequence;
        List<String> deduped = new ArrayList<>();
        String lastLine = null;
        int count = 0;
        for (String line : sequence) {
            if (line.equals(lastLine)) {
                count++;
            } else {
                if (count > 1) {
                    deduped.set(deduped.size() - 1, lastLine + " (repeated " + count + " times)");
                }
                deduped.add(line);
                lastLine = line;
                count = 1;
            }
        }
        if (count > 1) {
            deduped.set(deduped.size() - 1, lastLine + " (repeated " + count + " times)");
        }
        return deduped;
    }
}
