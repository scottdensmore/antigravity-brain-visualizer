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

import java.io.IOException;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests {@link InsightsService} over sessions gathered from the store. */
class InsightsServiceTest extends PostgresTest {

    @BeforeEach
    void reset() throws SQLException {
        resetStore();
    }

    private InsightsService insights() {
        return new InsightsService(new SessionCollector(new SessionRepository(dataSource())));
    }

    @Test
    void aggregatesSessionsFromASource() throws IOException {
        seedSession(
            "fake",
            "s1",
            "[{\"created_at\":\"2026-06-19T10:00:00Z\",\"tool_calls\":[{\"name\":\"Bash\"}]}," +
            "{\"type\":\"ERROR_MESSAGE\",\"status\":\"ERROR\",\"error\":\"boom\",\"created_at\":\"2026-06-19T10:00:05Z\"}]",
            "{\"recommendations\":[\"Use a linter\"],\"issues\":[{\"error\":\"Flaky test\"}]}",
            1L
        );
        seedSession(
            "fake",
            "s2",
            "[{\"created_at\":\"2026-06-19T11:00:00Z\",\"tool_calls\":[{\"name\":\"Read\"}]}]",
            null,
            2L
        );

        InsightsReport r = insights().forFlavor("fake");

        assertEquals("fake", r.flavor());
        assertEquals(2, r.sessionCount());
        assertEquals(2, r.sampledSessions());
        assertEquals(1, r.analyzedSessions());
        assertEquals(2, r.toolCallTotal());
        assertEquals(1, r.sessionsWithErrors());
        assertEquals(1, r.cleanSessions());
        assertTrue(r.topTools().stream().anyMatch(n -> n.name().equals("Bash")));
        assertTrue(r.topRecommendations().stream().anyMatch(n -> n.name().equals("Use a linter")));
        assertTrue(r.topIssues().stream().anyMatch(n -> n.name().equals("Flaky test")));
    }

    @Test
    void capsScanAtMaxSessionsButReportsTrueTotal() throws IOException {
        int total = SessionCollector.MAX_SESSIONS + 50;
        for (int i = 0; i < total; i++) {
            seedSession("fake", "s" + i, "[]", null, i);
        }

        InsightsReport r = insights().forFlavor("fake");
        assertEquals(total, r.sessionCount());
        assertEquals(SessionCollector.MAX_SESSIONS, r.sampledSessions());
    }
}
