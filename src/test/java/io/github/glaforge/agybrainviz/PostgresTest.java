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

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for repository tests, backed by a real Postgres.
 *
 * <p>H2's Postgres compatibility mode supports neither {@code jsonb} nor the conditional
 * {@code ON CONFLICT ... WHERE} the ingest upsert relies on, so an in-memory stand-in would test a
 * different database than the one that ships.
 *
 * <p>One container is started for the whole test JVM and left for Ryuk to reap, rather than one per
 * test class: container startup dominates the runtime of these tests. The schema comes from the same
 * {@code db/schema.sql} the application bootstraps with, so a schema change can never pass the tests
 * and then fail in production.
 */
abstract class PostgresTest {

    private static PostgreSQLContainer<?> postgres;
    private static HikariDataSource dataSource;

    @BeforeAll
    static void startPostgres() throws IOException, SQLException {
        if (postgres != null) return;
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);

        SchemaBootstrap.apply(dataSource, SchemaBootstrap.readSchema());
    }

    static DataSource dataSource() {
        return dataSource;
    }

    /** Empties a table so each test starts from a known state. */
    static void truncate(String table) throws SQLException {
        try (
            Connection connection = dataSource.getConnection();
            Statement stmt = connection.createStatement()
        ) {
            stmt.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY");
        }
    }
}
