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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministically mines reusable patterns out of many sessions' normalized steps (plus any cached
 * AI analysis). This is the structural, no-LLM half of the "skill / AGENTS.md miner": it surfaces
 *
 * <ul>
 *   <li><b>recurring tool sequences</b> — consecutive tool-call n-grams (2- and 3-grams) that show
 *       up across multiple sessions, i.e. workflows worth codifying as a skill;
 *   <li><b>failure → fix pairs</b> — errors and how they were circumvented, rolled up from the
 *       per-session analyses, i.e. durable AGENTS.md lessons;
 *   <li><b>recommendations</b> — the improvement backlog the analyses already proposed.
 * </ul>
 *
 * <p>Like {@link FleetInsights} it works purely on the shared step schema, so it is source-agnostic
 * and fully unit-testable without any I/O. An LLM pass (see {@code MinerService}) then phrases this
 * evidence into concrete skills and AGENTS.md rules.
 */
final class PatternMiner {

    private PatternMiner() {}

    private static final int TOP_N = 12;
    /** A tool sequence must recur in at least this many sessions to count as a workflow. */
    private static final int MIN_SEQUENCE_SESSIONS = 2;

    /** The structural evidence mined from a set of sessions. */
    record Patterns(
        List<NameCount> toolSequences,
        List<FixPair> failureFixes,
        List<NameCount> recommendations,
        int analyzedSessions
    ) {}

    static Patterns mine(List<FleetInsights.Session> sessions) {
        // Tool sequences are counted by how many distinct sessions they appear in (not raw
        // occurrences), so a single session looping the same pair doesn't masquerade as a fleet-wide
        // workflow.
        Map<String, Integer> sequenceFreq = new LinkedHashMap<>();
        Map<String, FixAccumulator> fixes = new LinkedHashMap<>();
        Map<String, Integer> recFreq = new LinkedHashMap<>();
        int analyzed = 0;

        for (FleetInsights.Session session : sessions) {
            for (String seq : sequencesIn(session.steps())) {
                sequenceFreq.merge(seq, 1, Integer::sum);
            }

            JsonNode summary = session.cachedSummary();
            if (summary == null || summary.isMissingNode() || summary.isNull()) continue;
            analyzed++;

            // Like tool sequences, failures and recommendations are counted by session breadth, so a
            // single session repeating the same item doesn't inflate its "×N sessions" count.
            Set<String> sessionErrors = new LinkedHashSet<>();
            for (JsonNode issue : summary.path("issues")) {
                String error = clean(issue.path("error").asText(""));
                if (error.isBlank()) continue;
                String fix = clean(issue.path("circumvention").asText(""));
                FixAccumulator acc = fixes.computeIfAbsent(
                    error.toLowerCase(),
                    k -> new FixAccumulator(error)
                );
                acc.offerFix(fix);
                if (sessionErrors.add(error.toLowerCase())) acc.incrementSession();
            }
            Set<String> sessionRecs = new LinkedHashSet<>();
            for (JsonNode rec : summary.path("recommendations")) {
                String text = clean(rec.asText(""));
                if (!text.isBlank() && sessionRecs.add(text)) recFreq.merge(text, 1, Integer::sum);
            }
        }

        return new Patterns(
            topSequences(sequenceFreq),
            topFixes(fixes),
            topCounts(recFreq),
            analyzed
        );
    }

    /**
     * The set of distinct consecutive tool-call 2- and 3-grams in one session's step order. Shared
     * with {@link FleetInsights} so a workflow drill-down keys on exactly what the miner reported.
     */
    static Set<String> sequencesIn(List<JsonNode> steps) {
        List<String> tools = new ArrayList<>();
        for (JsonNode step : steps) {
            JsonNode toolCalls = step.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode tool : toolCalls) {
                    tools.add(tool.path("name").asText("unknown"));
                }
            }
        }

        Set<String> grams = new LinkedHashSet<>();
        for (int n = 2; n <= 3; n++) {
            for (int i = 0; i + n <= tools.size(); i++) {
                grams.add(String.join(" → ", tools.subList(i, i + n)));
            }
        }
        return grams;
    }

    private static List<NameCount> topSequences(Map<String, Integer> freq) {
        return freq
            .entrySet()
            .stream()
            .filter(e -> e.getValue() >= MIN_SEQUENCE_SESSIONS)
            // Most-recurring first; on a tie prefer the longer (more specific) workflow.
            .sorted((a, b) -> {
                int byCount = Integer.compare(b.getValue(), a.getValue());
                if (byCount != 0) return byCount;
                return Integer.compare(b.getKey().length(), a.getKey().length());
            })
            .limit(TOP_N)
            .map(e -> new NameCount(e.getKey(), e.getValue()))
            .toList();
    }

    private static List<NameCount> topCounts(Map<String, Integer> freq) {
        return freq
            .entrySet()
            .stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(TOP_N)
            .map(e -> new NameCount(e.getKey(), e.getValue()))
            .toList();
    }

    private static List<FixPair> topFixes(Map<String, FixAccumulator> fixes) {
        return fixes
            .values()
            .stream()
            .sorted((a, b) -> Integer.compare(b.count, a.count))
            .limit(TOP_N)
            .map(f -> new FixPair(f.error, f.fix, f.count))
            .toList();
    }

    /** Accumulates the session-count and first known fix for one normalized error. */
    private static final class FixAccumulator {

        private final String error;
        private String fix = "";
        private int count = 0;

        FixAccumulator(String error) {
            this.error = error;
        }

        void offerFix(String candidateFix) {
            if (fix.isBlank() && candidateFix != null && !candidateFix.isBlank()) {
                fix = candidateFix;
            }
        }

        void incrementSession() {
            count++;
        }
    }

    private static String clean(String text) {
        if (text == null) return "";
        String stripped = text.strip();
        return stripped.length() > 200 ? stripped.substring(0, 200) : stripped;
    }
}
