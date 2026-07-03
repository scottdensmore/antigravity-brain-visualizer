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
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The analysis-quality eval harness: gathers a source's sessions via {@link SessionCollector}, grades
 * every session that already has a cached AI analysis with the deterministic {@link EvalScorer}, and
 * rolls the results into an {@link EvalReport} scoreboard. No LLM calls, so it is fast and
 * deterministic; the report is labelled with the active model so runs can be compared after a
 * model/prompt change (change the model, recompute analyses, re-run the eval).
 */
@Singleton
public class EvalService {

    private static final int WORST_CASES = 10;

    private final SessionCollector collector;
    private final AiConfig aiConfig;

    @Inject
    public EvalService(SessionCollector collector, AiConfig aiConfig) {
        this.collector = collector;
        this.aiConfig = aiConfig;
    }

    public EvalReport forFlavor(String flavor) throws IOException {
        SessionCollector.Collected collected = collector.collect(flavor);

        List<EvalCaseResult> results = new ArrayList<>();
        for (FleetInsights.Session session : collected.sessions()) {
            JsonNode analysis = session.cachedSummary();
            if (analysis == null || analysis.isMissingNode() || analysis.isNull()) continue;
            String id = session.id() == null ? "unknown" : session.id();
            results.add(EvalScorer.score(id, session.steps(), analysis));
        }

        double avgScore = results.isEmpty()
            ? 0.0
            : round1(results.stream().mapToInt(EvalCaseResult::score).average().orElse(0.0));

        List<NameCount> passRates = new ArrayList<>();
        for (String check : EvalScorer.checkNames()) {
            int passing = (int) results.stream().filter(r -> r.passed().contains(check)).count();
            passRates.add(new NameCount(check, passing));
        }

        List<EvalCaseResult> worst = results
            .stream()
            .sorted(Comparator.comparingInt(EvalCaseResult::score))
            .limit(WORST_CASES)
            .toList();

        return new EvalReport(
            flavor,
            collected.totalSessionCount(),
            collected.sessions().size(),
            results.size(),
            avgScore,
            modelLabel(),
            passRates,
            worst
        );
    }

    private String modelLabel() {
        return aiConfig.provider() == AiConfig.Provider.OLLAMA
            ? "ollama · " + aiConfig.ollamaModel()
            : "gemini · " + aiConfig.geminiModel();
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
