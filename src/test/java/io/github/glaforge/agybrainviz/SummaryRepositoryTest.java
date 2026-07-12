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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests the cached-analysis store that replaces SummaryCache and the in-session summary files. */
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
}
