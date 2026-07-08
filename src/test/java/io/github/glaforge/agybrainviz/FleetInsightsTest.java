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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the source-agnostic fleet aggregator. */
class FleetInsightsTest {

    private static final ObjectMapper M = new ObjectMapper();

    private ObjectNode toolStep(String created, String toolName) {
        ObjectNode step = M.createObjectNode();
        step.put("created_at", created);
        step.putArray("tool_calls").addObject().put("name", toolName);
        return step;
    }

    private ObjectNode errorStep(String created, String error) {
        ObjectNode step = M.createObjectNode();
        step.put("created_at", created);
        step.put("type", "ERROR_MESSAGE");
        step.put("status", "ERROR");
        step.put("error", error);
        return step;
    }

    /** A Codex/Claude-style failed tool output: the error text lives in {@code content}. */
    private ObjectNode outputErrorStep(String created, String content) {
        ObjectNode step = M.createObjectNode();
        step.put("created_at", created);
        step.put("type", "FUNCTION_OUTPUT");
        step.put("source", "TOOL");
        step.put("status", "ERROR");
        step.put("content", content);
        return step;
    }

    private JsonNode summary(List<String> recommendations, List<String> issueErrors) {
        ObjectNode s = M.createObjectNode();
        var recs = s.putArray("recommendations");
        recommendations.forEach(recs::add);
        var issues = s.putArray("issues");
        issueErrors.forEach(e -> issues.addObject().put("error", e));
        return s;
    }

    @Test
    void aggregatesToolsErrorsOutcomesAndBacklog() {
        FleetInsights.Session a = new FleetInsights.Session(
            List.of(
                toolStep("2026-06-19T10:00:00Z", "Read"),
                toolStep("2026-06-19T10:00:05Z", "Bash"),
                errorStep("2026-06-19T10:00:10Z", "NullPointerException at X")
            ),
            summary(List.of("Add a lint rule", "Write more tests"), List.of("NPE in parser"))
        );
        FleetInsights.Session b = new FleetInsights.Session(
            List.of(toolStep("2026-06-19T11:00:00Z", "Read")),
            null
        );

        InsightsReport r = FleetInsights.aggregate("antigravity-cli", 5, List.of(a, b));

        assertEquals("antigravity-cli", r.flavor());
        assertEquals(5, r.sessionCount());
        assertEquals(2, r.sampledSessions());
        assertEquals(1, r.analyzedSessions());
        assertEquals(3, r.toolCallTotal());
        assertEquals(1, r.sessionsWithErrors());
        assertEquals(1, r.cleanSessions());
        assertEquals(1.5, r.avgToolsPerSession());
        // Only session A spans time (10s); B is a single step.
        assertEquals(10.0, r.avgDurationSeconds());

        assertEquals("Read", r.topTools().get(0).name());
        assertEquals(2, r.topTools().get(0).count());
        assertEquals("Bash", r.topTools().get(1).name());

        assertTrue(r.topErrors().stream().anyMatch(n -> n.name().contains("NullPointerException")));
        assertTrue(
            r.topRecommendations().stream().anyMatch(n -> n.name().equals("Add a lint rule"))
        );
        assertTrue(r.topIssues().stream().anyMatch(n -> n.name().equals("NPE in parser")));
    }

    @Test
    void countsToolFrequencyAcrossSessions() {
        FleetInsights.Session a = new FleetInsights.Session(
            List.of(toolStep("2026-06-19T10:00:00Z", "Bash")),
            null
        );
        FleetInsights.Session b = new FleetInsights.Session(
            List.of(
                toolStep("2026-06-19T11:00:00Z", "Bash"),
                toolStep("2026-06-19T11:00:01Z", "Grep")
            ),
            null
        );

        InsightsReport r = FleetInsights.aggregate("codex", 2, List.of(a, b));
        assertEquals("Bash", r.topTools().get(0).name());
        assertEquals(2, r.topTools().get(0).count());
        assertEquals(0, r.analyzedSessions());
        assertEquals(2, r.cleanSessions());
    }

    @Test
    void groupsSimilarToolErrorsAfterNormalization() {
        // Two runs of the same underlying failure differing only by a volatile path (as raw Codex /
        // Claude tool errors do) must collapse into a single bucket, not two.
        FleetInsights.Session a = new FleetInsights.Session(
            List.of(
                outputErrorStep(
                    "2026-06-19T10:00:00Z",
                    "Error: ENOENT: no such file or directory, open '/Users/alice/a.txt'"
                )
            ),
            null
        );
        FleetInsights.Session b = new FleetInsights.Session(
            List.of(
                outputErrorStep(
                    "2026-06-19T11:00:00Z",
                    "Error: ENOENT: no such file or directory, open '/home/bob/b.txt'"
                )
            ),
            null
        );

        InsightsReport r = FleetInsights.aggregate("codex", 2, List.of(a, b));

        assertEquals(2, r.sessionsWithErrors());
        assertEquals(1, r.topErrors().size());
        assertEquals(2, r.topErrors().get(0).count());
        assertTrue(r.topErrors().get(0).name().contains("'<v>'"));
    }

    private ObjectNode userStep(String content) {
        ObjectNode step = M.createObjectNode();
        step.put("type", "USER_INPUT");
        step.put("source", "USER_EXPLICIT");
        step.put("content", content);
        return step;
    }

    @Test
    void drillsDownToTheSessionsBehindATallyItem() {
        // Session A: uses Read, hits an NPE, and its analysis recommends a lint rule.
        FleetInsights.Session a = new FleetInsights.Session(
            "sess-a",
            List.of(
                userStep("fix the parser"),
                toolStep("2026-06-19T10:00:00Z", "Read"),
                errorStep("2026-06-19T10:00:05Z", "NullPointerException at X")
            ),
            summary(List.of("Add a lint rule"), List.of("NPE in parser"))
        );
        // Session B: only uses Bash, no errors, no analysis.
        FleetInsights.Session b = new FleetInsights.Session(
            "sess-b",
            List.of(toolStep("2026-06-19T11:00:00Z", "Bash")),
            null
        );
        List<FleetInsights.Session> sessions = List.of(a, b);

        // Tool "Read" → only session A; its title comes from the first user prompt.
        List<SessionRef> readers = FleetInsights.sessionsFor("tool", "Read", sessions);
        assertEquals(1, readers.size());
        assertEquals("sess-a", readers.get(0).id());
        assertEquals("fix the parser", readers.get(0).title());

        // Tool "Bash" → only session B.
        assertEquals("sess-b", FleetInsights.sessionsFor("tool", "Bash", sessions).get(0).id());

        // Error / recommendation / issue all resolve to session A, keyed exactly as the report shows.
        assertEquals(
            "sess-a",
            FleetInsights.sessionsFor("error", "NullPointerException at X", sessions).get(0).id()
        );
        assertEquals(
            1,
            FleetInsights.sessionsFor("recommendation", "Add a lint rule", sessions).size()
        );
        assertEquals(1, FleetInsights.sessionsFor("issue", "NPE in parser", sessions).size());

        // Unknown category or non-matching key → empty.
        assertTrue(FleetInsights.sessionsFor("bogus", "Read", sessions).isEmpty());
        assertTrue(FleetInsights.sessionsFor("tool", "Grep", sessions).isEmpty());
    }

    @Test
    void drillsDownIntoAWorkflowSequence() {
        // Session A runs Read → Edit → Bash; session B only runs Read.
        FleetInsights.Session a = new FleetInsights.Session(
            "sess-a",
            List.of(
                toolStep("2026-06-19T10:00:00Z", "Read"),
                toolStep("2026-06-19T10:00:01Z", "Edit"),
                toolStep("2026-06-19T10:00:02Z", "Bash")
            ),
            null
        );
        FleetInsights.Session b = new FleetInsights.Session(
            "sess-b",
            List.of(toolStep("2026-06-19T11:00:00Z", "Read")),
            null
        );

        List<SessionRef> refs = FleetInsights.sessionsFor(
            "workflow",
            "Read → Edit → Bash",
            List.of(a, b)
        );
        assertEquals(1, refs.size());
        assertEquals("sess-a", refs.get(0).id());
    }

    @Test
    void drilldownTitlePrefersTheAnalysisShortTitle() {
        ObjectNode summaryWithTitle = M.createObjectNode();
        summaryWithTitle.put("shortTitle", "Parser fix");
        summaryWithTitle.putArray("recommendations").add("Add a lint rule");
        FleetInsights.Session s = new FleetInsights.Session(
            "sess-a",
            List.of(userStep("some long prompt that should be ignored")),
            summaryWithTitle
        );

        SessionRef ref = FleetInsights
            .sessionsFor("recommendation", "Add a lint rule", List.of(s))
            .get(0);
        assertEquals("Parser fix", ref.title());
    }

    @Test
    void handlesNoSessions() {
        InsightsReport r = FleetInsights.aggregate("codex", 0, List.of());
        assertEquals(0, r.sampledSessions());
        assertEquals(0.0, r.avgToolsPerSession());
        assertEquals(0.0, r.avgDurationSeconds());
        assertTrue(r.topTools().isEmpty());
    }
}
