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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests the cached-analysis store that replaced the former in-session summary files. */
class SummaryRepositoryTest extends PostgresTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private SummaryRepository summaries;

    @BeforeEach
    void setUp() throws SQLException {
        summaries = new SummaryRepository(dataSource());
        truncate("summaries");
    }

    @Test
    void findReturnsWhatWasWritten() throws Exception {
        summaries.upsert(
            "codex",
            "a",
            "{\"shortTitle\":\"Fixed build\",\"summary\":\"s\"}",
            "Fixed build"
        );

        Optional<String> found = summaries.find("codex", "a");
        assertTrue(found.isPresent());
        // Round-trips as JSON, not as an opaque string.
        assertEquals("Fixed build", MAPPER.readTree(found.get()).get("shortTitle").asText());
    }

    @Test
    void upsertReplacesAnExistingSummary() {
        summaries.upsert("codex", "a", "{\"summary\":\"first\"}", "t1");
        summaries.upsert("codex", "a", "{\"summary\":\"second\"}", "t2");

        assertTrue(summaries.find("codex", "a").get().contains("second"));
    }

    @Test
    void findIsEmptyForAnUnknownSession() {
        assertEquals(Optional.empty(), summaries.find("codex", "nope"));
    }

    @Test
    void deleteRemovesTheCachedSummary() {
        summaries.upsert("codex", "a", "{\"summary\":\"s\"}", "t");
        summaries.delete("codex", "a");
        assertFalse(summaries.find("codex", "a").isPresent());
    }

    @Test
    void summariesAreKeyedBySourceAndId() {
        summaries.upsert("codex", "same", "{\"summary\":\"codex\"}", "t");
        summaries.upsert("claude-code", "same", "{\"summary\":\"claude\"}", "t");

        assertTrue(summaries.find("codex", "same").get().contains("codex"));
        assertTrue(summaries.find("claude-code", "same").get().contains("claude"));
    }

    @Test
    void upsertReportsWhetherItWroteAndSkipsUnchangedContent() {
        assertTrue(summaries.upsert("codex", "a", "{\"summary\":\"one\"}", "t"), "first write");
        assertFalse(
            summaries.upsert("codex", "a", "{\"summary\":\"one\"}", "t"),
            "identical content should be a no-op"
        );
        assertTrue(
            summaries.upsert("codex", "a", "{\"summary\":\"two\"}", "t"),
            "changed content should write"
        );
    }

    @Test
    void manifestPublishesTheContentHashPerSession() {
        summaries.upsert("codex", "a", "{\"summary\":\"one\"}", "t");
        summaries.upsert("codex", "b", "{\"summary\":\"two\"}", "t");
        summaries.upsert("claude-code", "c", "{\"summary\":\"other\"}", "t");

        Map<String, String> manifest = summaries.manifest("codex");
        assertEquals(2, manifest.size());
        // The published hash is the shared content hash the client also computes.
        assertEquals(Hashing.sha256Hex("{\"summary\":\"one\"}"), manifest.get("a"));
        assertEquals(Hashing.sha256Hex("{\"summary\":\"two\"}"), manifest.get("b"));
    }

    @Test
    void aNullTitleDoesNotWipeAnExistingShortTitle() throws Exception {
        summaries.upsert("codex", "a", "{\"summary\":\"first\"}", "Nice Title");
        // A summary-only push carries no title; it must not erase the label set earlier.
        summaries.upsert("codex", "a", "{\"summary\":\"second\"}", null);

        assertEquals("Nice Title", storedShortTitle("codex", "a"));
    }

    private String storedShortTitle(String source, String id) throws SQLException {
        try (
            var c = dataSource().getConnection();
            var s = c.prepareStatement(
                "SELECT short_title FROM summaries WHERE source=? AND session_id=?"
            )
        ) {
            s.setString(1, source);
            s.setString(2, id);
            try (var rs = s.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
