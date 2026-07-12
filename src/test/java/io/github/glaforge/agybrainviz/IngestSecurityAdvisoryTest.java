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

import io.github.glaforge.agybrainviz.IngestSecurityAdvisory.Posture;
import org.junit.jupiter.api.Test;

/** The boot-time classification that decides what the operator gets warned about. */
class IngestSecurityAdvisoryTest {

    @Test
    void aConfiguredTokenIsAuthenticatedRegardlessOfTheRequireFlag() {
        assertEquals(Posture.AUTHENTICATED, IngestSecurityAdvisory.classify(new IngestConfig("t")));
        assertEquals(
            Posture.AUTHENTICATED,
            IngestSecurityAdvisory.classify(new IngestConfig("t", true))
        );
    }

    @Test
    void noTokenAndNoRequirementIsTheOpenLocalhostDefault() {
        assertEquals(Posture.OPEN, IngestSecurityAdvisory.classify(new IngestConfig("")));
    }

    @Test
    void requiringAuthWithoutATokenIsAMisconfiguration() {
        assertEquals(
            Posture.MISCONFIGURED,
            IngestSecurityAdvisory.classify(new IngestConfig("", true))
        );
    }

    @Test
    void aWhitespaceOnlyTokenCountsAsNoToken() {
        // token() treats blank as unset, so a whitespace-only token with auth required is still a
        // misconfiguration, not a (useless) configured token.
        assertEquals(
            Posture.MISCONFIGURED,
            IngestSecurityAdvisory.classify(new IngestConfig("   ", true))
        );
    }
}
