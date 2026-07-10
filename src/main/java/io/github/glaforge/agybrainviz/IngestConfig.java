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
 * Whether the ingest endpoints require a shared secret.
 *
 * <p>{@code INGEST_TOKEN} is read from a real environment variable, else a {@code .env} file. Unset
 * leaves {@code /api/ingest} open, which is the right default while the server only listens on
 * localhost. Set it as soon as the server is reachable from another machine — those endpoints are the
 * only ones that write.
 *
 * <p>Reading through this bean (rather than {@code System.getenv} inline) gives {@link
 * IngestAuthFilter} a test seam, matching {@link AiConfig}.
 */
@Singleton
public class IngestConfig {

    private final String ingestToken;

    @Inject
    public IngestConfig() {
        this(env("INGEST_TOKEN"));
    }

    // Test seam: construct with an explicit token, bypassing the environment.
    IngestConfig(String ingestToken) {
        this.ingestToken = ingestToken;
    }

    /** @return the shared token, or empty when the ingest endpoints are unguarded. */
    public Optional<String> token() {
        return (ingestToken == null || ingestToken.isBlank())
            ? Optional.empty()
            : Optional.of(ingestToken);
    }

    private static String env(String key) {
        String value = System.getenv(key);
        if (value == null) value = DotEnv.get(key);
        return value == null ? "" : value;
    }
}
