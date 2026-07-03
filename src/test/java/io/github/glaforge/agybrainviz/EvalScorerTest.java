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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the deterministic analysis-quality scorer. */
class EvalScorerTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** A fully-formed, high-quality analysis with a title, summary, flow, recs, and a fixed issue. */
    private ObjectNode goodAnalysis() {
        ObjectNode a = M.createObjectNode();
        a.put("shortTitle", "Fixed the build");
        a.put("summary", "The agent updated the JDK and the build passed.");
        a.putArray("flow").add("Read config").add("Edited mise.toml");
        a.putArray("recommendations").add("Pin the JDK with mise");
        a
            .putArray("issues")
            .addObject()
            .put("error", "Build failed")
            .put("circumvention", "Used JDK 25");
        return a;
    }

    private ObjectNode errorStep() {
        ObjectNode s = M.createObjectNode();
        s.put("type", "ERROR_MESSAGE");
        s.put("status", "ERROR");
        return s;
    }

    @Test
    void perfectAnalysisScoresFull() {
        EvalCaseResult r = EvalScorer.score("s1", List.of(errorStep()), goodAnalysis());
        assertEquals(100, r.score());
        assertTrue(r.failed().isEmpty());
        assertEquals("Fixed the build", r.title());
        assertEquals(EvalScorer.checkNames().size(), r.passed().size());
    }

    @Test
    void emptyAnalysisFailsSchemaAndRecommendations() {
        JsonNode empty = M.createObjectNode().put("summary", "");
        EvalCaseResult r = EvalScorer.score("s1", List.of(), empty);
        assertTrue(r.failed().contains("schema-complete"));
        assertTrue(r.failed().contains("has-recommendations"));
        // concise-summary requires a non-empty summary too.
        assertTrue(r.failed().contains("concise-summary"));
        // 3 of 6 checks fail (schema, recommendations, concise); the rest pass vacuously => 50.
        assertEquals(50, r.score());
        // No issues and no transcript errors => coverage passes vacuously.
        assertTrue(r.passed().contains("error-coverage"));
        assertTrue(r.passed().contains("issues-have-fixes"));
    }

    @Test
    void issueWithoutFixFailsThatCheck() {
        ObjectNode a = goodAnalysis();
        ((ArrayNode) a.get("issues")).removeAll();
        a.withArray("issues").addObject().put("error", "Something broke").put("circumvention", "");
        EvalCaseResult r = EvalScorer.score("s1", List.of(), a);
        assertTrue(r.failed().contains("issues-have-fixes"));
    }

    @Test
    void missingIssueForErroredTranscriptFailsCoverage() {
        ObjectNode a = goodAnalysis();
        ((ArrayNode) a.get("issues")).removeAll(); // no issues, but the transcript errored
        EvalCaseResult r = EvalScorer.score("s1", List.of(errorStep()), a);
        assertTrue(r.failed().contains("error-coverage"));
    }

    @Test
    void failedCommandCountsAsAnErrorForCoverage() {
        // A RUN_COMMAND step whose output reports failure is an error (matching FleetInsights), so an
        // analysis with no issues must fail error-coverage.
        ObjectNode failedCmd = M.createObjectNode();
        failedCmd.put("type", "RUN_COMMAND");
        failedCmd.put("content", "$ ./gradlew build\nThe command failed with exit code 1");

        ObjectNode a = goodAnalysis();
        ((ArrayNode) a.get("issues")).removeAll();
        EvalCaseResult r = EvalScorer.score("s1", List.of(failedCmd), a);
        assertTrue(r.failed().contains("error-coverage"));
    }

    @Test
    void degenerateSummaryFailsAndOversizedSummaryIsNotConcise() {
        ObjectNode repeat = goodAnalysis();
        repeat.put("summary", "spam spam spam spam spam spam spam done");
        assertTrue(EvalScorer.score("s1", List.of(), repeat).failed().contains("not-degenerate"));

        ObjectNode blob = goodAnalysis();
        blob.put("summary", "x".repeat(120)); // one giant token => Base64-like
        assertTrue(EvalScorer.score("s2", List.of(), blob).failed().contains("not-degenerate"));

        ObjectNode huge = goodAnalysis();
        huge.put("summary", "word ".repeat(600)); // > 2000 chars, but not repeated 6x in a row
        EvalCaseResult r = EvalScorer.score("s3", List.of(), huge);
        assertTrue(r.failed().contains("concise-summary"));
    }

    @Test
    void checkNamesAreStableAndComplete() {
        // The scorer must classify every declared check for a given case.
        EvalCaseResult r = EvalScorer.score("s1", List.of(), goodAnalysis());
        int classified = r.passed().size() + r.failed().size();
        assertEquals(EvalScorer.checkNames().size(), classified);
        assertFalse(EvalScorer.checkNames().isEmpty());
    }
}
