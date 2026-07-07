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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for the error-grouping normalizer. */
class ErrorNormalizerTest {

    @Test
    void collapsesQuotedValuesSoSameFailureGroups() {
        String a = "Error: ENOENT: no such file or directory, open '/Users/alice/project/a.txt'";
        String b = "Error: ENOENT: no such file or directory, open '/home/bob/work/b.txt'";
        assertEquals(ErrorNormalizer.normalize(a), ErrorNormalizer.normalize(b));
        assertTrue(ErrorNormalizer.normalize(a).contains("'<v>'"));
    }

    @Test
    void collapsesBarePaths() {
        assertEquals(
            "cannot open <path>",
            ErrorNormalizer.normalize("cannot open /Users/a/b/c.txt")
        );
        assertEquals(
            ErrorNormalizer.normalize("cannot open /Users/a/b/c.txt"),
            ErrorNormalizer.normalize("cannot open /var/tmp/other/z.log")
        );
    }

    @Test
    void collapsesNumbersButKeepsAlphanumericCodes() {
        assertEquals(
            "listen EADDRINUSE :::<n>",
            ErrorNormalizer.normalize("listen EADDRINUSE :::3000")
        );
        // A number fused to letters (e.g. a TS error code) is a signature, not volatile noise.
        String ts = ErrorNormalizer.normalize("error TS2304: Cannot find name 'foo'");
        assertTrue(ts.contains("TS2304"), ts);
        assertTrue(ts.contains("'<v>'"), ts);
    }

    @Test
    void collapsesHexAndUuid() {
        assertEquals("segfault at <hex>", ErrorNormalizer.normalize("segfault at 0xdeadbeef"));
        assertEquals(
            "run <id> failed",
            ErrorNormalizer.normalize("run 550e8400-e29b-41d4-a716-446655440000 failed")
        );
    }

    @Test
    void prefersAStrongErrorLineOverANoisyFirstLine() {
        String blob = "$ ./run.sh\nProcess exited with code 2\nError: connection refused";
        assertEquals("Error: connection refused", ErrorNormalizer.normalize(blob));
    }

    @Test
    void fallsBackToExitLineWhenNoStrongSignal() {
        String blob = "some output\nProcess exited with code 137";
        assertEquals("Process exited with code <n>", ErrorNormalizer.normalize(blob));
    }

    @Test
    void stripsAntigravityStepPrefixAndMetadata() {
        String blob =
            "Encountered error in step execution: The thing broke\nCreated At: 2026-06-19";
        assertEquals("The thing broke", ErrorNormalizer.normalize(blob));
    }

    @Test
    void boundsOutputToAMaxLength() {
        String huge = "Error: " + "x".repeat(5000);
        String key = ErrorNormalizer.normalize(huge);
        assertTrue(key.length() <= 100, "key should be truncated");
        assertTrue(key.startsWith("Error:"));
    }

    @Test
    void returnsUnknownForBlankInput() {
        assertEquals("Unknown error", ErrorNormalizer.normalize(""));
        assertEquals("Unknown error", ErrorNormalizer.normalize("   \n  "));
        assertEquals("Unknown error", ErrorNormalizer.normalize(null));
    }
}
