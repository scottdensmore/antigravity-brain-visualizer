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

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Optional;

/**
 * Guards the read/compute API with a shared bearer token when {@code API_TOKEN} is set.
 *
 * <p>These routes serve the stored transcripts, spend LLM tokens (summarize, mine, optimize, eval's
 * judge), and mutate saved eval runs — on a server reachable off-localhost they need guarding just
 * as much as ingest. Ingest keeps its own filter and token ({@link IngestAuthFilter}): the ingest
 * token is distributed to every pushing machine, the API token only to readers.
 *
 * <p>With no token configured the filter waves everything through — the server is then presumed to
 * be listening on localhost only.
 */
@Singleton
@ServerFilter(
    patterns = {
        "/api/analysis/**",
        "/api/brain/**",
        "/api/eval/**",
        "/api/insights/**",
        "/api/mine/**",
        "/api/optimize/**",
    }
)
public class ApiAuthFilter {

    private final ApiConfig config;

    @Inject
    public ApiAuthFilter(ApiConfig config) {
        this.config = config;
    }

    /** @return a 401/503 to short-circuit the request, or {@code null} to let it proceed. */
    @RequestFilter
    @Nullable
    public HttpResponse<?> guard(HttpRequest<?> request) {
        Optional<String> expected = config.token();
        if (expected.isEmpty()) {
            // Same fail-closed contract as ingest: an operator who demanded auth without providing
            // a token gets a 503 (their misconfiguration), never a silent fallback to open.
            if (config.requireAuth()) {
                return HttpResponse
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "The API is unavailable."));
            }
            return null; // unguarded by configuration
        }

        String header = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (!BearerTokens.matches(expected.get(), header)) {
            return HttpResponse
                .unauthorized()
                .body(
                    Map.of("error", "This API requires a valid Authorization: Bearer <API_TOKEN>.")
                );
        }
        return null;
    }
}
