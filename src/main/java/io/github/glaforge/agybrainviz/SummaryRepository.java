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
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Stores AI analysis summaries in the shared database, keyed by the session's {@code (source, id)}.
 * This replaces the former file-backed cache (per-session {@code summary.json} on disk), so a summary
 * computed on one machine is available from another.
 */
@Singleton
public class SummaryRepository {

    private static final String UPSERT_SQL = """
        INSERT INTO summaries (source, session_id, summary, short_title, content_hash, updated_at)
        VALUES (?, ?, ?::jsonb, ?, ?, now())
        ON CONFLICT (source, session_id) DO UPDATE
           SET summary = excluded.summary,
               -- A summary-only push carries no title; keep the one we have rather than wipe it.
               short_title = COALESCE(excluded.short_title, summaries.short_title),
               content_hash = excluded.content_hash,
               updated_at = now()
           WHERE summaries.content_hash IS DISTINCT FROM excluded.content_hash
        """;

    private static final String FIND_SQL =
        "SELECT summary FROM summaries WHERE source = ? AND session_id = ?";

    private static final String MANIFEST_SQL =
        "SELECT session_id, content_hash FROM summaries WHERE source = ? AND content_hash IS NOT NULL";

    private static final String DELETE_SQL =
        "DELETE FROM summaries WHERE source = ? AND session_id = ?";

    private final DataSource dataSource;

    public SummaryRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** @return the cached analysis JSON for a session, or empty when none is stored. */
    public Optional<String> find(String source, String id) {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement stmt = connection.prepareStatement(FIND_SQL)
        ) {
            stmt.setString(1, source);
            stmt.setString(2, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("Could not read the cached summary for " + id, e);
        }
    }

    /**
     * Stores (or replaces) the analysis JSON and short title for a session.
     *
     * @return {@code true} if a row was written, {@code false} if the content was already stored (an
     *     unchanged re-push is a no-op, so a summary manifest lets a client skip it).
     */
    public boolean upsert(String source, String id, String summaryJson, String title) {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement stmt = connection.prepareStatement(UPSERT_SQL)
        ) {
            stmt.setString(1, source);
            stmt.setString(2, id);
            stmt.setObject(3, summaryJson, Types.OTHER);
            if (title == null || title.isBlank()) {
                stmt.setNull(4, Types.VARCHAR);
            } else {
                stmt.setString(4, title.trim());
            }
            stmt.setString(5, Hashing.sha256Hex(summaryJson));
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StoreUnavailableException("Could not store the summary for " + id, e);
        }
    }

    /**
     * Every stored {@code session_id -> contentHash} for a source, so an ingest client can push only
     * the summaries the store is missing or that changed since it last synced.
     */
    public Map<String, String> manifest(String source) {
        Map<String, String> manifest = new LinkedHashMap<>();
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement stmt = connection.prepareStatement(MANIFEST_SQL)
        ) {
            stmt.setString(1, source);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) manifest.put(
                    rs.getString("session_id"),
                    rs.getString("content_hash")
                );
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException(
                "Could not read the summary manifest for " + source,
                e
            );
        }
        return manifest;
    }

    /** Removes any cached summary for a session (used on a forced recompute). */
    public void delete(String source, String id) {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement stmt = connection.prepareStatement(DELETE_SQL)
        ) {
            stmt.setString(1, source);
            stmt.setString(2, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new StoreUnavailableException("Could not delete the summary for " + id, e);
        }
    }
}
