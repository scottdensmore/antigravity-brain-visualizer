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

import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Reads and writes ingested trajectories, keyed by the stable {@code (source, id)} pair.
 *
 * <p>That key is the whole de-duplication mechanism: the id is derived from the trajectory itself
 * (a Claude Code session UUID, a Codex rollout id, an Antigravity session directory), never from the
 * path it was found at. So the same session pushed from two machines, or pushed twice from one,
 * lands on exactly one row.
 */
@Singleton
public class SessionRepository {

    // The `WHERE content_hash <> excluded.content_hash` guard is what makes re-pushing an unchanged
    // trajectory a no-op: the row is left alone and executeUpdate reports 0.
    private static final String UPSERT_SQL = """
        INSERT INTO sessions (source, id, title, updated_at, steps, content_hash, source_mtime)
        VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
        ON CONFLICT (source, id) DO UPDATE
           SET title = excluded.title,
               updated_at = excluded.updated_at,
               steps = excluded.steps,
               content_hash = excluded.content_hash,
               source_mtime = excluded.source_mtime,
               ingested_at = now()
         WHERE sessions.content_hash <> excluded.content_hash
        """;

    private static final String MANIFEST_SQL =
        "SELECT id, content_hash FROM sessions WHERE source = ?";

    private static final String COUNT_SQL = "SELECT count(*) FROM sessions WHERE source = ?";

    // The cached AI short title labels a session once analysis has run, matching the old file path
    // (which preferred short_title.txt); the ingest-derived title is the fallback. `id` is the
    // tie-break so identical mtimes list (and page) deterministically.
    private static final String LIST_SQL = """
        SELECT s.id, COALESCE(NULLIF(m.short_title, ''), s.title) AS title, s.source_mtime
        FROM sessions s
        LEFT JOIN summaries m ON m.source = s.source AND m.session_id = s.id
        WHERE s.source = ?
        ORDER BY s.updated_at DESC, s.id
        LIMIT ? OFFSET ?
        """;

    private static final String STEPS_SQL =
        "SELECT steps FROM sessions WHERE source = ? AND id = ?";

    private static final String EXISTS_SQL = "SELECT 1 FROM sessions WHERE source = ? AND id = ?";

    // The cross-session features want the newest N sessions with their cached
    // analysis; a LEFT JOIN keeps sessions that have no summary yet.
    private static final String COLLECT_SQL = """
        SELECT s.id, s.steps, m.summary
        FROM sessions s
        LEFT JOIN summaries m ON m.source = s.source AND m.session_id = s.id
        WHERE s.source = ?
        ORDER BY s.updated_at DESC, s.id
        LIMIT ?
        """;

    private final DataSource dataSource;

    public SessionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** One ingested trajectory, as pushed by a client and normalized by the server. */
    public record Session(
        String source,
        String id,
        String title,
        long sourceMtime,
        String stepsJson,
        String contentHash
    ) {}

    /**
     * Inserts the session, or updates it when its content changed.
     *
     * @return whether a row was written; {@code false} means an identical trajectory was already
     *     stored, so this push was a no-op.
     */
    public boolean upsert(Session session) {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement stmt = connection.prepareStatement(UPSERT_SQL)
        ) {
            stmt.setString(1, session.source());
            stmt.setString(2, session.id());
            stmt.setString(3, session.title());
            stmt.setTimestamp(4, new Timestamp(session.sourceMtime()));
            stmt.setObject(5, session.stepsJson(), Types.OTHER);
            stmt.setString(6, session.contentHash());
            stmt.setLong(7, session.sourceMtime());
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StoreUnavailableException("Could not store session " + session.id(), e);
        }
    }

    /**
     * @return every stored {@code id -> contentHash} for a source, so a client can skip pushing the
     *     trajectories that have not changed. Sending the hashes rather than the transcripts is what
     *     keeps a sync cheap once the corpus is large.
     */
    public Map<String, String> manifest(String source) {
        Map<String, String> manifest = new LinkedHashMap<>();
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement stmt = connection.prepareStatement(MANIFEST_SQL)
        ) {
            stmt.setString(1, source);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) manifest.put(rs.getString("id"), rs.getString("content_hash"));
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("Could not read the manifest for " + source, e);
        }
        return manifest;
    }

    /** A session with its normalized steps and any cached analysis, for the cross-session features. */
    public record CollectedSession(String id, String stepsJson, String summaryJson) {}

    /**
     * @return one entry per session ({@code id}, {@code summary}, {@code updatedAt}) for a source,
     *     newest first — the shape the frontend's conversation list expects. {@code updatedAt} is the
     *     source file's modification time in epoch milliseconds, matching the former file-based list.
     */
    public List<Map<String, String>> listConversations(String source, int limit, int offset) {
        List<Map<String, String>> out = new ArrayList<>();
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement stmt = connection.prepareStatement(LIST_SQL)
        ) {
            stmt.setString(1, source);
            stmt.setInt(2, limit);
            stmt.setInt(3, offset);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> info = new HashMap<>();
                    info.put("id", rs.getString("id"));
                    info.put("summary", rs.getString("title"));
                    info.put("updatedAt", Long.toString(rs.getLong("source_mtime")));
                    out.add(info);
                }
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("Could not list sessions for " + source, e);
        }
        return out;
    }

    /** @return the session's normalized steps as a JSON array string, or {@code null} if not found. */
    public String steps(String source, String id) {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement stmt = connection.prepareStatement(STEPS_SQL)
        ) {
            stmt.setString(1, source);
            stmt.setString(2, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("Could not read the transcript for " + id, e);
        }
    }

    /** @return whether a session with this id is stored under the source. */
    public boolean exists(String source, String id) {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement stmt = connection.prepareStatement(EXISTS_SQL)
        ) {
            stmt.setString(1, source);
            stmt.setString(2, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("Could not check for session " + id, e);
        }
    }

    /** @return up to {@code limit} sessions for a source, newest first, each with its cached summary. */
    public List<CollectedSession> collect(String source, int limit) {
        List<CollectedSession> out = new ArrayList<>();
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement stmt = connection.prepareStatement(COLLECT_SQL)
        ) {
            stmt.setString(1, source);
            stmt.setInt(2, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(
                        new CollectedSession(
                            rs.getString("id"),
                            rs.getString("steps"),
                            rs.getString("summary")
                        )
                    );
                }
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("Could not collect sessions for " + source, e);
        }
        return out;
    }

    /** @return how many trajectories are stored for a source. */
    public int countBySource(String source) {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement stmt = connection.prepareStatement(COUNT_SQL)
        ) {
            stmt.setString(1, source);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("Could not count sessions for " + source, e);
        }
    }
}
