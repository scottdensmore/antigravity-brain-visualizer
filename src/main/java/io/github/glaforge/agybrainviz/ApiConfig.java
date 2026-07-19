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
 * Whether the read/compute API endpoints require a shared secret.
 *
 * <p>{@code API_TOKEN} is read from a real environment variable, else a {@code .env} file. Unset
 * leaves the non-ingest {@code /api} routes open, which is the right default while the server only
 * listens on localhost. Set it as soon as the server is reachable from another machine — those
 * routes serve the stored transcripts and can spend LLM tokens (summarize, mine, optimize) or
 * mutate saved eval runs, so they are worth guarding just as much as ingest.
 *
 * <p>{@code API_REQUIRE_AUTH} mirrors {@code INGEST_REQUIRE_AUTH}: an opt-in fail-closed switch so
 * a missing {@code API_TOKEN} can't silently leave the API exposed.
 *
 * <p>Kept separate from {@link IngestConfig} on purpose: the ingest token is handed to every
 * machine that pushes transcripts, while the API token belongs to the people allowed to read them —
 * sharing one secret would widen the reader set to every pushing machine.
 */
@Singleton
public class ApiConfig {

    private final String apiToken;
    private final boolean requireAuth;

    @Inject
    public ApiConfig() {
        this(env("API_TOKEN"), IngestConfig.parseFlag(env("API_REQUIRE_AUTH")));
    }

    // Test seam: an explicit token with the open default (auth not required).
    ApiConfig(String apiToken) {
        this(apiToken, false);
    }

    // Test seam: construct with explicit values, bypassing the environment.
    ApiConfig(String apiToken, boolean requireAuth) {
        this.apiToken = apiToken;
        this.requireAuth = requireAuth;
    }

    /** @return the shared token, or empty when the API endpoints are unguarded. */
    public Optional<String> token() {
        return (apiToken == null || apiToken.isBlank())
            ? Optional.empty()
            : Optional.of(apiToken.trim());
    }

    /** @return whether the API must be authenticated, even at the cost of rejecting it when no token is set. */
    public boolean requireAuth() {
        return requireAuth;
    }

    private static String env(String key) {
        String value = System.getenv(key);
        if (value == null) value = DotEnv.get(key);
        return value == null ? "" : value;
    }
}
