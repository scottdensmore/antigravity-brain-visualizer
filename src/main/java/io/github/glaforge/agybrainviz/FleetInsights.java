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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure aggregation of many sessions' normalized steps (plus any cached AI analysis) into an
 * {@link InsightsReport}. Works on the shared step schema, so it is source-agnostic and fully
 * unit-testable without any I/O.
 */
final class FleetInsights {

    private FleetInsights() {}

    private static final int TOP_N = 10;

    /** One session's input: its normalized timeline steps and (optionally) its cached analysis JSON. */
    record Session(List<JsonNode> steps, JsonNode cachedSummary) {}

    static InsightsReport aggregate(String flavor, int totalSessionCount, List<Session> sessions) {
        Map<String, Integer> toolFreq = new LinkedHashMap<>();
        Map<String, Integer> errorFreq = new LinkedHashMap<>();
        Map<String, Integer> recFreq = new LinkedHashMap<>();
        Map<String, Integer> issueFreq = new LinkedHashMap<>();

        int toolCallTotal = 0;
        int sessionsWithErrors = 0;
        int analyzedSessions = 0;
        long totalToolCalls = 0;
        List<Double> durations = new ArrayList<>();

        for (Session session : sessions) {
            int sessionTools = 0;
            boolean sessionHasError = false;
            long minTs = Long.MAX_VALUE;
            long maxTs = Long.MIN_VALUE;

            for (JsonNode step : session.steps()) {
                Long ts = epochMillis(step.path("created_at").asText(""));
                if (ts != null) {
                    minTs = Math.min(minTs, ts);
                    maxTs = Math.max(maxTs, ts);
                }

                JsonNode toolCalls = step.path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode tool : toolCalls) {
                        String name = tool.path("name").asText("unknown");
                        toolFreq.merge(name, 1, Integer::sum);
                        sessionTools++;
                        toolCallTotal++;
                    }
                }

                if (isError(step)) {
                    sessionHasError = true;
                    errorFreq.merge(errorKey(step), 1, Integer::sum);
                }
            }

            totalToolCalls += sessionTools;
            if (sessionHasError) sessionsWithErrors++;
            // Zero-span sessions (single step / identical timestamps) are excluded from the average
            // rather than counted as 0s, so trivial sessions don't drag the mean toward zero.
            if (maxTs > minTs) durations.add((maxTs - minTs) / 1000.0);

            JsonNode summary = session.cachedSummary();
            if (summary != null && !summary.isMissingNode() && !summary.isNull()) {
                analyzedSessions++;
                for (JsonNode rec : summary.path("recommendations")) {
                    String text = clean(rec.asText(""));
                    if (!text.isBlank()) recFreq.merge(text, 1, Integer::sum);
                }
                for (JsonNode issue : summary.path("issues")) {
                    String text = clean(issue.path("error").asText(""));
                    if (!text.isBlank()) issueFreq.merge(text, 1, Integer::sum);
                }
            }
        }

        int sampled = sessions.size();
        double avgTools = sampled == 0 ? 0.0 : (double) totalToolCalls / sampled;
        double avgDuration = durations.isEmpty()
            ? 0.0
            : durations.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        return new InsightsReport(
            flavor,
            totalSessionCount,
            sampled,
            analyzedSessions,
            toolCallTotal,
            sessionsWithErrors,
            sampled - sessionsWithErrors,
            round1(avgTools),
            round1(avgDuration),
            top(toolFreq),
            top(errorFreq),
            top(recFreq),
            top(issueFreq)
        );
    }

    private static boolean isError(JsonNode step) {
        String status = step.path("status").asText("");
        String type = step.path("type").asText("");
        String content = step.path("content").asText("");
        return (
            "ERROR".equals(status) ||
            "ERROR_MESSAGE".equals(type) ||
            ("RUN_COMMAND".equals(type) && content.contains("The command failed"))
        );
    }

    private static String errorKey(JsonNode step) {
        if ("RUN_COMMAND".equals(step.path("type").asText(""))) {
            return "Command execution failed";
        }
        String source = step.has("error")
            ? step.path("error").asText("")
            : step.path("content").asText("");
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (
                trimmed.isEmpty() ||
                trimmed.startsWith("Created At:") ||
                trimmed.startsWith("Completed At:")
            ) {
                continue;
            }
            String prefix = "Encountered error in step execution: ";
            if (trimmed.startsWith(prefix)) trimmed = trimmed.substring(prefix.length()).trim();
            return truncate(trimmed, 100);
        }
        return "Unknown error";
    }

    private static Long epochMillis(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    private static String clean(String text) {
        return truncate(text == null ? "" : text.strip(), 200);
    }

    private static String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static List<NameCount> top(Map<String, Integer> freq) {
        return freq
            .entrySet()
            .stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(TOP_N)
            .map(e -> new NameCount(e.getKey(), e.getValue()))
            .toList();
    }
}
