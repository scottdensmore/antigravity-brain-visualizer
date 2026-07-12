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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Base class for repository tests, backed by the real Postgres of {@link TestPostgres}.
 *
 * <p>H2's Postgres compatibility mode supports neither {@code jsonb} nor the conditional
 * {@code ON CONFLICT ... WHERE} the ingest upsert relies on, so an in-memory stand-in would test a
 * different database than the one that ships. The schema comes from the same {@code db/schema.sql}
 * the application bootstraps with, so a schema change cannot pass the tests and then fail in
 * production.
 */
abstract class PostgresTest {

    static DataSource dataSource() {
        return TestPostgres.dataSource();
    }

    /** Empties a table so each test starts from a known state. */
    static void truncate(String table) throws SQLException {
        try (
            Connection connection = dataSource().getConnection();
            Statement stmt = connection.createStatement()
        ) {
            stmt.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY");
        }
    }

    /** Clears the session and summary tables — the state the read path serves from. */
    static void resetStore() throws SQLException {
        truncate("sessions");
        truncate("summaries");
    }

    /** Seeds one stored session (and optional cached summary) as ingest would have left it. */
    static void seedSession(
        String source,
        String id,
        String title,
        String stepsJson,
        String summaryJson,
        long mtime
    ) {
        new SessionRepository(dataSource())
            .upsert(
                new SessionRepository.Session(
                    source,
                    id,
                    title,
                    mtime,
                    stepsJson == null ? "[]" : stepsJson,
                    "h-" + source + "-" + id
                )
            );
        if (summaryJson != null) {
            new SummaryRepository(dataSource()).upsert(source, id, summaryJson, title);
        }
    }

    /** Convenience seed with a derived title. */
    static void seedSession(
        String source,
        String id,
        String stepsJson,
        String summaryJson,
        long mtime
    ) {
        seedSession(source, id, "title-" + id, stepsJson, summaryJson, mtime);
    }
}
