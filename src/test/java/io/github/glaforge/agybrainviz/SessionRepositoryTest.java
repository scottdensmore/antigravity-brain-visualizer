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

import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests the (source, id) keyed session store — the de-duplication mechanism — against Postgres. */
class SessionRepositoryTest extends PostgresTest {

    private SessionRepository sessions;

    @BeforeEach
    void setUp() throws SQLException {
        sessions = new SessionRepository(dataSource());
        truncate("sessions");
    }

    private SessionRepository.Session session(String source, String id, String steps, String hash) {
        return new SessionRepository.Session(source, id, "title-" + id, 1_000L, steps, hash);
    }

    @Test
    void storesANewTrajectory() {
        assertTrue(sessions.upsert(session("codex", "a", "[{\"type\":\"MESSAGE\"}]", "h1")));
        assertEquals(1, sessions.countBySource("codex"));
        assertEquals(Map.of("a", "h1"), sessions.manifest("codex"));
    }

    @Test
    void rePushingIdenticalContentIsANoOp() {
        assertTrue(sessions.upsert(session("codex", "a", "[]", "h1")));
        // Same content hash: the row must be left alone, and the caller told nothing was written.
        assertFalse(sessions.upsert(session("codex", "a", "[]", "h1")));
        assertEquals(1, sessions.countBySource("codex"));
    }

    @Test
    void rePushingChangedContentUpdatesInPlace() {
        sessions.upsert(session("codex", "a", "[]", "h1"));
        assertTrue(sessions.upsert(session("codex", "a", "[{\"type\":\"MESSAGE\"}]", "h2")));

        assertEquals(1, sessions.countBySource("codex"), "an update must not create a second row");
        assertEquals(Map.of("a", "h2"), sessions.manifest("codex"));
    }

    @Test
    void theSameIdUnderTwoSourcesIsTwoTrajectories() {
        // The id alone is not the key: a Codex and a Claude Code session could share one.
        assertTrue(sessions.upsert(session("codex", "same", "[]", "h1")));
        assertTrue(sessions.upsert(session("claude-code", "same", "[]", "h2")));

        assertEquals(1, sessions.countBySource("codex"));
        assertEquals(1, sessions.countBySource("claude-code"));
    }

    @Test
    void theManifestIsScopedToItsSource() {
        sessions.upsert(session("codex", "a", "[]", "h1"));
        sessions.upsert(session("claude-code", "b", "[]", "h2"));

        assertEquals(Map.of("a", "h1"), sessions.manifest("codex"));
        assertEquals(Map.of("b", "h2"), sessions.manifest("claude-code"));
        assertTrue(sessions.manifest("antigravity-cli").isEmpty());
    }

    @Test
    void countAndManifestAreEmptyForAnUnknownSource() {
        assertEquals(0, sessions.countBySource("nope"));
        assertTrue(sessions.manifest("nope").isEmpty());
    }
}
