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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;

/**
 * Guards the ingest endpoints with a shared bearer token when {@code INGEST_TOKEN} is set.
 *
 * <p>These are the only endpoints that write, and the only ones a remote machine calls, so they are
 * the only ones worth guarding. With no token configured the filter waves everything through — the
 * server is then presumed to be listening on localhost only.
 */
@Singleton
@ServerFilter("/api/ingest/**")
public class IngestAuthFilter {

    private static final String BEARER = "Bearer ";

    private final IngestConfig config;

    @Inject
    public IngestAuthFilter(IngestConfig config) {
        this.config = config;
    }

    /** @return a 401/503 to short-circuit the request, or {@code null} to let it proceed. */
    @RequestFilter
    @Nullable
    public HttpResponse<?> guard(HttpRequest<?> request) {
        Optional<String> expected = config.token();
        if (expected.isEmpty()) {
            // No token configured. Normally that's the localhost-only default, so wave through — but
            // if the operator opted into INGEST_REQUIRE_AUTH they want auth enforced, and there is no
            // token any request could present. Fail closed: reject with a 503 (a server-side
            // misconfiguration the client can't fix by authenticating), never fall back to open. The
            // body stays generic — the specific cause (missing INGEST_TOKEN) is for the operator's
            // startup log, not an unauthenticated caller.
            if (config.requireAuth()) {
                return HttpResponse
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Ingest is unavailable."));
            }
            return null; // unguarded by configuration
        }

        String header = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER) || !matches(expected.get(), header)) {
            return HttpResponse
                .unauthorized()
                .body(
                    Map.of("error", "Ingest requires a valid Authorization: Bearer <INGEST_TOKEN>.")
                );
        }
        return null;
    }

    // Compare without leaking the token's length or contents through timing.
    private static boolean matches(String expected, String header) {
        byte[] presented = header
            .substring(BEARER.length())
            .trim()
            .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented);
    }
}
