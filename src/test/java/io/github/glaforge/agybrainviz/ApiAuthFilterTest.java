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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

/** Tests the shared-secret guard on the read/compute API endpoints. */
class ApiAuthFilterTest {

    private static final String TOKEN = "api-s3cret";

    private HttpResponse<?> guard(String configuredToken, String authorization) {
        return guardWith(new ApiConfig(configuredToken), authorization);
    }

    private HttpResponse<?> guardWith(ApiConfig config, String authorization) {
        HttpRequest<?> request = authorization == null
            ? HttpRequest.GET("/api/brain/conversations")
            : HttpRequest.GET("/api/brain/conversations").header("Authorization", authorization);
        return new ApiAuthFilter(config).guard(request);
    }

    @Test
    void lettingEveryRequestThroughWhenNoTokenIsConfigured() {
        // The default: the server is presumed to be on localhost only.
        assertNull(guard(null, null));
        assertNull(guard("", null));
        assertNull(guard("   ", "Bearer whatever"));
    }

    @Test
    void acceptsTheConfiguredBearerToken() {
        assertNull(guard(TOKEN, "Bearer " + TOKEN));
    }

    @Test
    void rejectsAMissingHeader() {
        assertEquals(HttpStatus.UNAUTHORIZED, guard(TOKEN, null).status());
    }

    @Test
    void rejectsAWrongToken() {
        assertEquals(HttpStatus.UNAUTHORIZED, guard(TOKEN, "Bearer nope").status());
    }

    @Test
    void rejectsATokenThatIsMerelyAPrefixOfTheRealOne() {
        // A length-insensitive comparison would wave this through.
        assertEquals(HttpStatus.UNAUTHORIZED, guard(TOKEN, "Bearer api-s3c").status());
    }

    @Test
    void rejectsANonBearerScheme() {
        assertEquals(HttpStatus.UNAUTHORIZED, guard(TOKEN, "Basic " + TOKEN).status());
        assertEquals(HttpStatus.UNAUTHORIZED, guard(TOKEN, TOKEN).status());
    }

    @Test
    void theIngestTokenIsNotAcceptedHere() {
        // The ingest token is handed to every pushing machine; it must not unlock the reads.
        assertEquals(HttpStatus.UNAUTHORIZED, guard(TOKEN, "Bearer some-ingest-token").status());
    }

    @Test
    void failsClosedWhenAuthIsRequiredButNoTokenIsConfigured() {
        ApiConfig requireButNoToken = new ApiConfig("", true);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, guardWith(requireButNoToken, null).status());
        assertEquals(
            HttpStatus.SERVICE_UNAVAILABLE,
            guardWith(requireButNoToken, "Bearer anything").status()
        );
    }

    @Test
    void requiringAuthDoesNotChangeTheValidTokenPath() {
        ApiConfig requireWithToken = new ApiConfig(TOKEN, true);
        assertNull(guardWith(requireWithToken, "Bearer " + TOKEN));
        assertEquals(HttpStatus.UNAUTHORIZED, guardWith(requireWithToken, "Bearer nope").status());
    }
}
