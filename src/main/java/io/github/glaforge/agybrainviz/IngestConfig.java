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
import java.util.Locale;
import java.util.Optional;

/**
 * Whether the ingest endpoints require a shared secret.
 *
 * <p>{@code INGEST_TOKEN} is read from a real environment variable, else a {@code .env} file. Unset
 * leaves {@code /api/ingest} open, which is the right default while the server only listens on
 * localhost. Set it as soon as the server is reachable from another machine — those endpoints are the
 * only ones that write.
 *
 * <p>{@code INGEST_REQUIRE_AUTH} is an opt-in fail-closed switch for operators who never want the
 * open default: when it is truthy, {@link IngestAuthFilter} rejects all ingest until a token is
 * configured, so a missing {@code INGEST_TOKEN} can't silently leave the writes exposed.
 *
 * <p>Reading through this bean (rather than {@code System.getenv} inline) gives {@link
 * IngestAuthFilter} a test seam, matching {@link AiConfig}.
 */
@Singleton
public class IngestConfig {

    private final String ingestToken;
    private final boolean requireAuth;

    @Inject
    public IngestConfig() {
        this(env("INGEST_TOKEN"), parseFlag(env("INGEST_REQUIRE_AUTH")));
    }

    // Test seam: an explicit token with the open default (auth not required).
    IngestConfig(String ingestToken) {
        this(ingestToken, false);
    }

    // Test seam: construct with explicit values, bypassing the environment.
    IngestConfig(String ingestToken, boolean requireAuth) {
        this.ingestToken = ingestToken;
        this.requireAuth = requireAuth;
    }

    /** @return the shared token, or empty when the ingest endpoints are unguarded. */
    public Optional<String> token() {
        return (ingestToken == null || ingestToken.isBlank())
            ? Optional.empty()
            : Optional.of(ingestToken.trim());
    }

    /** @return whether ingest must be authenticated, even at the cost of rejecting it when no token is set. */
    public boolean requireAuth() {
        return requireAuth;
    }

    /** Reads a boolean-ish env flag: {@code true}/{@code 1}/{@code yes}/{@code on} (any case) is true. */
    static boolean parseFlag(String value) {
        if (value == null) return false;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            default -> false;
        };
    }

    private static String env(String key) {
        String value = System.getenv(key);
        if (value == null) value = DotEnv.get(key);
        return value == null ? "" : value;
    }
}
