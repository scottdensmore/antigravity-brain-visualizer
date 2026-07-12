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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests how the ingest guard reads its configuration. */
class IngestConfigTest {

    @Test
    void aBlankOrMissingTokenLeavesIngestOpen() {
        assertTrue(new IngestConfig(null).token().isEmpty());
        assertTrue(new IngestConfig("").token().isEmpty());
        assertTrue(new IngestConfig("   ").token().isEmpty());
    }

    @Test
    void aTokenIsTrimmedAndExposed() {
        assertEquals("abc", new IngestConfig("  abc  ").token().orElseThrow());
    }

    @Test
    void requireAuthDefaultsToFalse() {
        assertFalse(new IngestConfig("abc").requireAuth());
    }

    @Test
    void requireAuthAcceptsCommonAffirmatives() {
        for (String yes : new String[] { "true", "TRUE", "  True ", "1", "yes", "on" }) {
            assertTrue(IngestConfig.parseFlag(yes), yes + " should be truthy");
        }
    }

    @Test
    void requireAuthTreatsAnythingElseAsFalse() {
        for (String no : new String[] { null, "", "false", "0", "no", "off", "maybe" }) {
            assertFalse(IngestConfig.parseFlag(no), no + " should be falsey");
        }
    }
}
