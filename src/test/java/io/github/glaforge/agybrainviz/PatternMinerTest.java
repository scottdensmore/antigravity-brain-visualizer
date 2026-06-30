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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the source-agnostic structural pattern miner. */
class PatternMinerTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** Builds a session whose steps each carry one of the given tool names, in order. */
    private FleetInsights.Session sessionOfTools(JsonNode summary, String... toolNames) {
        List<JsonNode> steps = new ArrayList<>();
        for (String name : toolNames) {
            ObjectNode step = M.createObjectNode();
            step.putArray("tool_calls").addObject().put("name", name);
            steps.add(step);
        }
        return new FleetInsights.Session(steps, summary);
    }

    private JsonNode summary(List<String> recommendations, List<String[]> issueErrorFix) {
        ObjectNode s = M.createObjectNode();
        var recs = s.putArray("recommendations");
        recommendations.forEach(recs::add);
        var issues = s.putArray("issues");
        issueErrorFix.forEach(ef ->
            issues.addObject().put("error", ef[0]).put("circumvention", ef[1])
        );
        return s;
    }

    @Test
    void surfacesToolSequencesRecurringAcrossSessions() {
        // Both sessions run Read → Edit → Bash; only one runs Grep → Read.
        FleetInsights.Session a = sessionOfTools(null, "Read", "Edit", "Bash");
        FleetInsights.Session b = sessionOfTools(null, "Read", "Edit", "Bash");
        FleetInsights.Session c = sessionOfTools(null, "Grep", "Read");

        PatternMiner.Patterns p = PatternMiner.mine(List.of(a, b, c));

        // The shared 3-gram is the most-recurring, most-specific workflow.
        assertEquals("Read → Edit → Bash", p.toolSequences().get(0).name());
        assertEquals(2, p.toolSequences().get(0).count());
        // A sequence seen in only one session is below the recurrence threshold and dropped.
        assertFalse(p.toolSequences().stream().anyMatch(n -> n.name().equals("Grep → Read")));
    }

    @Test
    void countsSequencesPerSessionNotPerOccurrence() {
        // One session loops Read → Bash five times; that is still just one session.
        FleetInsights.Session looper = sessionOfTools(
            null,
            "Read",
            "Bash",
            "Read",
            "Bash",
            "Read",
            "Bash"
        );
        FleetInsights.Session other = sessionOfTools(null, "Read", "Bash");

        PatternMiner.Patterns p = PatternMiner.mine(List.of(looper, other));

        assertEquals("Read → Bash", p.toolSequences().get(0).name());
        assertEquals(2, p.toolSequences().get(0).count());
    }

    @Test
    void rollsUpFailureFixesAndRecommendations() {
        FleetInsights.Session a = sessionOfTools(
            summary(
                List.of("Add a lint rule"),
                List.<String[]>of(new String[] { "Build fails on JDK 21", "Use JDK 25 via mise" })
            ),
            "Bash"
        );
        FleetInsights.Session b = sessionOfTools(
            summary(
                List.of("Add a lint rule"),
                List.<String[]>of(new String[] { "Build fails on JDK 21", "" })
            ),
            "Bash"
        );

        PatternMiner.Patterns p = PatternMiner.mine(List.of(a, b));

        assertEquals(2, p.analyzedSessions());
        // The same error from two sessions is one pair counted twice, keeping the first known fix.
        FixPair fix = p.failureFixes().get(0);
        assertEquals("Build fails on JDK 21", fix.error());
        assertEquals("Use JDK 25 via mise", fix.fix());
        assertEquals(2, fix.count());
        // The duplicated recommendation is deduped and counted.
        assertTrue(
            p
                .recommendations()
                .stream()
                .anyMatch(n -> n.name().equals("Add a lint rule") && n.count() == 2)
        );
    }

    @Test
    void countsFailuresAndRecommendationsPerSessionNotPerOccurrence() {
        // A single session that lists the same error and recommendation twice still counts once.
        FleetInsights.Session dup = sessionOfTools(
            summary(
                List.of("Add a lint rule", "Add a lint rule"),
                List.<String[]>of(
                    new String[] { "Build fails on JDK 21", "Use JDK 25" },
                    new String[] { "Build fails on JDK 21", "Use JDK 25" }
                )
            ),
            "Bash"
        );

        PatternMiner.Patterns p = PatternMiner.mine(List.of(dup));

        assertEquals(1, p.failureFixes().get(0).count());
        assertEquals(1, p.recommendations().get(0).count());
    }

    @Test
    void handlesNoSessions() {
        PatternMiner.Patterns p = PatternMiner.mine(List.of());
        assertEquals(0, p.analyzedSessions());
        assertTrue(p.toolSequences().isEmpty());
        assertTrue(p.failureFixes().isEmpty());
        assertTrue(p.recommendations().isEmpty());
    }
}
