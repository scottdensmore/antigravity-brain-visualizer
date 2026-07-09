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

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the store's tables on startup by running the bundled {@code db/schema.sql}.
 *
 * <p>Every statement in that script is {@code CREATE ... IF NOT EXISTS}, so running it on each boot —
 * and concurrently from several machines sharing one database — is safe. That idempotence is why a
 * migration framework isn't needed yet; introduce one only when the schema starts to evolve.
 *
 * <p>A database that is missing or unreachable must not stop the application from booting: the UI
 * and the {@code -h}/{@code -v} paths still work, and queries surface the failure per request. So a
 * failure here is logged, not thrown.
 */
@Singleton
public class SchemaBootstrap implements ApplicationEventListener<StartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaBootstrap.class);
    private static final String SCHEMA_RESOURCE = "/db/schema.sql";

    private final DataSource dataSource;

    public SchemaBootstrap(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        try {
            apply(dataSource, readSchema());
            LOG.info("Trajectory store schema is up to date.");
        } catch (IOException e) {
            // A missing resource is a packaging bug (e.g. the native image dropped it), not a
            // runtime condition, so it is worth shouting about even though we keep serving.
            LOG.error("Could not read {} — the store schema was not applied.", SCHEMA_RESOURCE, e);
        } catch (SQLException e) {
            LOG.warn(
                "Could not apply the store schema (is the database running? `docker compose up -d`): {}",
                e.getMessage()
            );
        }
    }

    /** Runs the whole script in one round trip; pgjdbc accepts a multi-statement simple query. */
    static void apply(DataSource dataSource, String schemaSql) throws SQLException {
        try (
            Connection connection = dataSource.getConnection();
            Statement stmt = connection.createStatement()
        ) {
            stmt.execute(schemaSql);
        }
    }

    static String readSchema() throws IOException {
        try (InputStream in = SchemaBootstrap.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IOException("Resource not found on the classpath: " + SCHEMA_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
