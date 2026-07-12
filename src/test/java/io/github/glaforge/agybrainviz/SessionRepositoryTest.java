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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests the (source, id) keyed session store — the de-duplication mechanism — against Postgres. */
class SessionRepositoryTest extends PostgresTest {

    private SessionRepository sessions;

    @BeforeEach
    void setUp() throws SQLException {
        sessions = new SessionRepository(dataSource());
        resetStore(); // sessions + summaries: listConversations now joins them
    }

    private SessionRepository.Session session(String source, String id, String steps, String hash) {
        return new SessionRepository.Session(source, id, "title-" + id, 1_000L, steps, hash);
    }

    private SessionRepository.Session sessionAt(String source, String id, long mtime) {
        return new SessionRepository.Session(source, id, "title-" + id, mtime, "[]", "h-" + id);
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

    @Test
    void listsConversationsNewestFirstScopedToSource() {
        sessions.upsert(sessionAt("codex", "old", 1_000L));
        sessions.upsert(sessionAt("codex", "new", 2_000L));
        sessions.upsert(sessionAt("claude-code", "other", 3_000L));

        List<Map<String, String>> list = sessions.listConversations("codex");
        assertEquals(2, list.size());
        assertEquals("new", list.get(0).get("id")); // newest first
        assertEquals("old", list.get(1).get("id"));
        assertEquals("title-new", list.get(0).get("summary"));
        assertEquals("2000", list.get(0).get("updatedAt")); // source mtime, epoch millis
    }

    @Test
    void listPrefersTheCachedAiShortTitleOverTheSessionTitle() {
        // After analysis runs, its concise short title should label the session in the list, the way
        // the old file path preferred short_title.txt over the raw first prompt.
        sessions.upsert(
            new SessionRepository.Session("codex", "a", "Long raw first prompt…", 1_000L, "[]", "h")
        );
        new SummaryRepository(dataSource())
            .upsert("codex", "a", "{\"summary\":\"s\"}", "Fixed the parser");

        List<Map<String, String>> list = sessions.listConversations("codex");
        assertEquals("Fixed the parser", list.get(0).get("summary"));
    }

    @Test
    void listFallsBackToTheSessionTitleWhenNoSummaryIsCached() {
        sessions.upsert(sessionAt("codex", "a", 1_000L));
        assertEquals("title-a", sessions.listConversations("codex").get(0).get("summary"));
    }

    @Test
    void stepsReturnsTheStoredArrayOrNull() {
        sessions.upsert(session("codex", "a", "[{\"type\":\"MESSAGE\"}]", "h1"));
        assertTrue(sessions.steps("codex", "a").contains("MESSAGE"));
        assertNull(sessions.steps("codex", "missing"));
    }

    @Test
    void existsReflectsWhetherASessionIsStored() {
        sessions.upsert(session("codex", "a", "[]", "h1"));
        assertTrue(sessions.exists("codex", "a"));
        assertFalse(sessions.exists("codex", "b"));
        assertFalse(sessions.exists("claude-code", "a"));
    }

    @Test
    void collectReturnsStepsAndJoinedSummaryNewestFirstUpToLimit() {
        sessions.upsert(sessionAt("codex", "old", 1_000L));
        sessions.upsert(sessionAt("codex", "mid", 2_000L));
        sessions.upsert(sessionAt("codex", "new", 3_000L));
        new SummaryRepository(dataSource()).upsert("codex", "new", "{\"summary\":\"s\"}", "t");

        List<SessionRepository.CollectedSession> collected = sessions.collect("codex", 2);
        assertEquals(2, collected.size());
        assertEquals("new", collected.get(0).id()); // newest first, limited to 2
        assertEquals("mid", collected.get(1).id());
        assertTrue(collected.get(0).summaryJson().contains("summary"));
        assertNull(collected.get(1).summaryJson()); // no summary joined
    }
}
