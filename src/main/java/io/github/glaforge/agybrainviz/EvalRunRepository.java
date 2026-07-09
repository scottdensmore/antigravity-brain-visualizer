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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Persists {@link EvalRunSnapshot}s to the shared store so eval quality can be tracked and compared
 * across runs — and across machines, which the file-backed predecessor could not do.
 *
 * <p>The history is capped at {@link #MAX_RUNS} rows globally; on overflow the oldest runs are
 * dropped. Concurrent writers from two machines are safe without an application lock: each save is a
 * single INSERT, and two overlapping cap-trims both converge on "keep the newest {@value #MAX_RUNS}".
 */
@Singleton
public class EvalRunRepository {

    /** Keep the history bounded; on overflow the oldest runs are dropped. */
    static final int MAX_RUNS = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<NameCount>> CHECK_RATES = new TypeReference<>() {};

    private static final String INSERT_SQL = """
        INSERT INTO eval_runs (saved_at, flavor, model_label, session_count, evaluated_sessions,
                               avg_score, check_pass_rates, judged, judged_sessions,
                               avg_faithfulness, avg_actionability, avg_clarity)
        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
        """;

    // Newest first. `saved_at` is ISO-8601, so it sorts chronologically; run_id breaks ties between
    // runs saved within the same instant.
    private static final String SELECT_SQL = """
        SELECT saved_at, flavor, model_label, session_count, evaluated_sessions, avg_score,
               check_pass_rates, judged, judged_sessions, avg_faithfulness, avg_actionability,
               avg_clarity
        FROM eval_runs
        WHERE flavor = ?
        ORDER BY saved_at DESC, run_id DESC
        """;

    private static final String TRIM_SQL = """
        DELETE FROM eval_runs
        WHERE run_id NOT IN (
            SELECT run_id FROM eval_runs ORDER BY saved_at DESC, run_id DESC LIMIT ?
        )
        """;

    private static final String DELETE_SQL = "DELETE FROM eval_runs WHERE saved_at = ?";

    private final DataSource dataSource;

    public EvalRunRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Records a snapshot of the given report (server-stamped) and returns it. */
    public EvalRunSnapshot save(EvalReport report) {
        return save(report, Instant.now().toString());
    }

    /**
     * Test seam: stamp the run with an explicit instant. {@code Instant.now()} repeats often enough
     * under a tight loop that two runs really can share a timestamp, which is why {@code saved_at}
     * is not the primary key — so tests need to force the collision rather than race for it.
     */
    EvalRunSnapshot save(EvalReport report, String savedAt) {
        EvalRunSnapshot snapshot = snapshotOf(report, savedAt);
        try (Connection connection = dataSource.getConnection()) {
            insert(connection, snapshot);
            trimToMaxRuns(connection);
        } catch (SQLException e) {
            throw new StoreUnavailableException("Could not save the eval run", e);
        }
        return snapshot;
    }

    /** All saved runs for a flavor, newest first. */
    public List<EvalRunSnapshot> list(String flavor) {
        List<EvalRunSnapshot> runs = new ArrayList<>();
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement stmt = connection.prepareStatement(SELECT_SQL)
        ) {
            stmt.setString(1, flavor);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) runs.add(readSnapshot(rs));
            }
        } catch (SQLException e) {
            throw new StoreUnavailableException("Could not list the eval runs", e);
        }
        return runs;
    }

    /**
     * Removes the saved run(s) with the given {@code savedAt} id.
     *
     * @return how many were removed; two runs can share an instant, and both are then removed.
     */
    public int delete(String savedAt) {
        if (savedAt == null || savedAt.isBlank()) return 0;
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement stmt = connection.prepareStatement(DELETE_SQL)
        ) {
            stmt.setString(1, savedAt);
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new StoreUnavailableException("Could not delete the eval run", e);
        }
    }

    private void insert(Connection connection, EvalRunSnapshot run) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(INSERT_SQL)) {
            stmt.setString(1, run.savedAt());
            stmt.setString(2, run.flavor());
            stmt.setString(3, run.modelLabel());
            stmt.setInt(4, run.sessionCount());
            stmt.setInt(5, run.evaluatedSessions());
            stmt.setDouble(6, run.avgScore());
            stmt.setObject(7, writeJson(run.checkPassRates()), Types.OTHER);
            stmt.setBoolean(8, run.judged());
            stmt.setInt(9, run.judgedSessions());
            stmt.setDouble(10, run.avgFaithfulness());
            stmt.setDouble(11, run.avgActionability());
            stmt.setDouble(12, run.avgClarity());
            stmt.executeUpdate();
        }
    }

    private void trimToMaxRuns(Connection connection) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(TRIM_SQL)) {
            stmt.setInt(1, MAX_RUNS);
            stmt.executeUpdate();
        }
    }

    private EvalRunSnapshot readSnapshot(ResultSet rs) throws SQLException {
        return new EvalRunSnapshot(
            rs.getString("saved_at"),
            rs.getString("flavor"),
            rs.getString("model_label"),
            rs.getInt("session_count"),
            rs.getInt("evaluated_sessions"),
            rs.getDouble("avg_score"),
            readCheckPassRates(rs.getString("check_pass_rates")),
            rs.getBoolean("judged"),
            rs.getInt("judged_sessions"),
            rs.getDouble("avg_faithfulness"),
            rs.getDouble("avg_actionability"),
            rs.getDouble("avg_clarity")
        );
    }

    private static String writeJson(List<NameCount> checkPassRates) {
        try {
            return MAPPER.writeValueAsString(checkPassRates == null ? List.of() : checkPassRates);
        } catch (Exception e) {
            return "[]";
        }
    }

    // A malformed tally must not make the whole run unlistable; the identity fields still read.
    private static List<NameCount> readCheckPassRates(String json) {
        try {
            return json == null ? List.of() : MAPPER.readValue(json, CHECK_RATES);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static EvalRunSnapshot snapshotOf(EvalReport report, String savedAt) {
        JudgeSummary judge = report.judge();
        boolean judged = judge != null && judge.ran();
        // Default the identity/label fields so a malformed body can't persist an unlistable snapshot.
        return new EvalRunSnapshot(
            savedAt,
            blankTo(report.flavor(), "unknown"),
            blankTo(report.modelLabel(), "unknown"),
            report.sessionCount(),
            report.evaluatedSessions(),
            report.avgScore(),
            report.checkPassRates() == null ? List.of() : report.checkPassRates(),
            judged,
            judged ? judge.judgedSessions() : 0,
            judged ? judge.avgFaithfulness() : 0.0,
            judged ? judge.avgActionability() : 0.0,
            judged ? judge.avgClarity() : 0.0
        );
    }
}
