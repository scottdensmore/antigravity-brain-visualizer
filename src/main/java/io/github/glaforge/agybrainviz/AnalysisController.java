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
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP surface for conversation analysis. The heavy lifting — chunking, parallel LLM dispatch,
 * consolidation, progress tracking, and caching — lives in {@link AnalysisOrchestrator}.
 */
@Controller("/api/analysis")
public class AnalysisController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Serdeable
    public record ProgressResponse(String phase, int progress) {}

    private final AiConfig aiConfig;
    private final AnalysisOrchestrator orchestrator;

    @Inject
    public AnalysisController(AiConfig aiConfig, AnalysisOrchestrator orchestrator) {
        this.aiConfig = aiConfig;
        this.orchestrator = orchestrator;
    }

    @Get(value = "/conversations/{id}/progress", produces = "application/json")
    public ProgressResponse getProgress(
        @PathVariable String id,
        @QueryValue Optional<String> flavor
    ) {
        AnalysisOrchestrator.ProgressState state = orchestrator.progress(
            flavor.orElse(AntigravityPaths.DEFAULT_FLAVOR),
            id
        );
        if (state == null) {
            return new ProgressResponse("", -1);
        }
        return new ProgressResponse(state.phase(), state.progress());
    }

    @ExecuteOn(TaskExecutors.IO)
    @Post(value = "/conversations/{id}/summarize", produces = "application/json")
    public String summarizeConversation(
        @PathVariable String id,
        @QueryValue Optional<Boolean> force,
        @QueryValue Optional<String> flavor
    ) throws IOException {
        if (!aiConfig.isConfigured()) {
            // Serialize via Jackson so the message is always valid JSON, regardless of its content.
            return MAPPER.writeValueAsString(Map.of("summary", aiConfig.notConfiguredMessage()));
        }

        return orchestrator.summarize(
            flavor.orElse(AntigravityPaths.DEFAULT_FLAVOR),
            id,
            force.orElse(false)
        );
    }
}
