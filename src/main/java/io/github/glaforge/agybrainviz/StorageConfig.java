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

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;

/**
 * Configuration for the shared Postgres store that holds ingested trajectories, their cached AI
 * analyses, and the eval-run history. Settings come from environment variables (or a {@code .env}
 * file):
 *
 * <ul>
 *   <li>{@code DATABASE_URL} — JDBC URL, default {@value #DEFAULT_DATABASE_URL}
 *   <li>{@code POSTGRES_USER}, {@code POSTGRES_PASSWORD}
 *   <li>{@code INGEST_TOKEN} — when set, the {@code /api/ingest} endpoints require
 *       {@code Authorization: Bearer <token>}. Unset leaves them open, which is fine while the
 *       server is only reachable on localhost.
 * </ul>
 *
 * <p>The defaults match the checked-in {@code docker-compose.yml}, so a developer who runs
 * {@code docker compose up -d} needs no configuration at all.
 *
 * <p>{@link Application#applyDatasourceSettings} feeds the same three connection values into
 * Micronaut's {@code datasources.default} properties. This bean exists so application code can ask
 * about the store (and the ingest token) without reaching into the environment inline; the
 * package-private constructor gives tests a seam.
 */
@Singleton
public class StorageConfig {

    static final String DEFAULT_DATABASE_URL = "jdbc:postgresql://localhost:5432/agentbrainviz";
    static final String DEFAULT_USER = "agentviz";
    static final String DEFAULT_PASSWORD = "agentviz";

    private final String databaseUrl;
    private final String user;
    private final String password;
    private final String ingestToken;

    @Inject
    public StorageConfig() {
        this(
            envOr("DATABASE_URL", DEFAULT_DATABASE_URL),
            envOr("POSTGRES_USER", DEFAULT_USER),
            envOr("POSTGRES_PASSWORD", DEFAULT_PASSWORD),
            env("INGEST_TOKEN")
        );
    }

    // Test seam: construct with explicit values, bypassing the environment.
    StorageConfig(String databaseUrl, String user, String password, String ingestToken) {
        this.databaseUrl = blankTo(databaseUrl, DEFAULT_DATABASE_URL);
        this.user = blankTo(user, DEFAULT_USER);
        this.password = blankTo(password, DEFAULT_PASSWORD);
        this.ingestToken = ingestToken;
    }

    public String databaseUrl() {
        return databaseUrl;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    /** @return the shared ingest token, or empty when the ingest endpoints are unguarded. */
    public Optional<String> ingestToken() {
        return (ingestToken == null || ingestToken.isBlank())
            ? Optional.empty()
            : Optional.of(ingestToken);
    }

    /**
     * A real environment variable, else the {@code .env} file, else empty. Mirrors the precedence in
     * {@link AiConfig} and {@link Application}.
     */
    private static String env(String key) {
        String value = System.getenv(key);
        if (value == null) value = DotEnv.get(key);
        return value == null ? "" : value;
    }

    private static String envOr(String key, String fallback) {
        String value = env(key);
        return value.isBlank() ? fallback : value;
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
