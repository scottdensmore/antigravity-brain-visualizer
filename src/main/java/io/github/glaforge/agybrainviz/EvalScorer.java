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
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministically grades one session's AI analysis against its transcript. These are the cheap,
 * no-LLM quality gates an eval harness runs before (or instead of) an LLM judge: each named check is
 * pass/fail, and the case score is the percentage of checks passed. Pure and source-agnostic, so it
 * is fully unit-testable and scores equally well whether the analysis came from cache or a fresh run.
 *
 * <p>The checks encode the contract the analysis pipeline promises (see {@code AnalyzerService}):
 *
 * <ul>
 *   <li>{@code schema-complete} — a title, a summary, and a non-empty flow are present;
 *   <li>{@code has-recommendations} — the analysis proposes at least one improvement;
 *   <li>{@code issues-have-fixes} — every reported issue records how it was circumvented;
 *   <li>{@code concise-summary} — the summary is present and not an oversized dump;
 *   <li>{@code not-degenerate} — no runaway token repetition or Base64-like blobs (the failure mode
 *       the pipeline's prompts explicitly guard against);
 *   <li>{@code error-coverage} — if the transcript hit errors, the analysis surfaces at least one
 *       issue.
 * </ul>
 */
final class EvalScorer {

    private EvalScorer() {}

    private static final int MAX_SUMMARY_CHARS = 2000;
    private static final int MAX_TOKEN_CHARS = 80;
    private static final int MAX_CONSECUTIVE_OCCURRENCES = 6;

    static EvalCaseResult score(String sessionId, List<JsonNode> steps, JsonNode analysis) {
        List<String> passed = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        String summary = analysis.path("summary").asText("");

        boolean schemaComplete =
            nonBlank(analysis.path("shortTitle").asText("")) &&
            nonBlank(summary) &&
            analysis.path("flow").isArray() &&
            analysis.path("flow").size() > 0;
        record(passed, failed, "schema-complete", schemaComplete);

        record(
            passed,
            failed,
            "has-recommendations",
            analysis.path("recommendations").isArray() &&
            analysis.path("recommendations").size() > 0
        );

        boolean issuesHaveFixes = true;
        for (JsonNode issue : analysis.path("issues")) {
            if (!nonBlank(issue.path("circumvention").asText(""))) {
                issuesHaveFixes = false;
                break;
            }
        }
        record(passed, failed, "issues-have-fixes", issuesHaveFixes);

        record(
            passed,
            failed,
            "concise-summary",
            !summary.isEmpty() && summary.length() <= MAX_SUMMARY_CHARS
        );

        record(passed, failed, "not-degenerate", !isDegenerate(summary));

        boolean transcriptHadError = stepsHaveError(steps);
        boolean analysisHasIssue =
            analysis.path("issues").isArray() && analysis.path("issues").size() > 0;
        record(passed, failed, "error-coverage", !transcriptHadError || analysisHasIssue);

        int total = passed.size() + failed.size();
        int score = total == 0 ? 0 : (int) Math.round(100.0 * passed.size() / total);

        String title = nonBlank(analysis.path("shortTitle").asText(""))
            ? analysis.path("shortTitle").asText()
            : sessionId;

        return new EvalCaseResult(sessionId, title, score, passed, failed);
    }

    /** The named checks in a stable order, so callers can report pass-rates over the full set. */
    static List<String> checkNames() {
        return List.of(
            "schema-complete",
            "has-recommendations",
            "issues-have-fixes",
            "concise-summary",
            "not-degenerate",
            "error-coverage"
        );
    }

    private static boolean stepsHaveError(List<JsonNode> steps) {
        // Reuse the canonical error test so the eval's "did the run fail?" definition stays in sync
        // with the fleet analytics (covers status/type errors and failed RUN_COMMAND steps).
        for (JsonNode step : steps) {
            if (FleetInsights.isError(step)) return true;
        }
        return false;
    }

    /** Detects the degeneration the pipeline warns against: giant tokens or long repeated runs. */
    private static boolean isDegenerate(String text) {
        if (text == null || text.isBlank()) return false;
        String[] tokens = text.trim().split("\\s+");
        int run = 1;
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].length() > MAX_TOKEN_CHARS) return true;
            if (i > 0 && tokens[i].equalsIgnoreCase(tokens[i - 1])) {
                run++;
                if (run >= MAX_CONSECUTIVE_OCCURRENCES) return true; // same token 6× in a row
            } else {
                run = 1;
            }
        }
        return false;
    }

    private static void record(List<String> passed, List<String> failed, String name, boolean ok) {
        (ok ? passed : failed).add(name);
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }
}
