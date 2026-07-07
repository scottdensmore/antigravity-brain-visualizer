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

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/** Unit tests for the centralized Antigravity on-disk layout helper. */
class AntigravityPathsTest {

    @Test
    void brainDirUsesGeminiRootFlavorAndBrain() {
        assertTrue(
            AntigravityPaths.brainDir("codex").endsWith(Paths.get(".gemini", "codex", "brain"))
        );
    }

    @Test
    void brainDirFallsBackToDefaultFlavorWhenBlank() {
        Path expectedTail = Paths.get(".gemini", AntigravityPaths.DEFAULT_FLAVOR, "brain");
        assertTrue(AntigravityPaths.brainDir(null).endsWith(expectedTail));
        assertTrue(AntigravityPaths.brainDir("").endsWith(expectedTail));
        assertEquals("antigravity-cli", AntigravityPaths.DEFAULT_FLAVOR);
    }

    @Test
    void logsDirResolvesGeneratedLogsUnderASession() {
        Path logs = AntigravityPaths.logsDir(Paths.get("/root/brain/sess-1"));
        assertTrue(logs.endsWith(Paths.get("sess-1", ".system_generated", "logs")));
    }

    @Test
    void logsDirByFlavorAndIdSpansTheWholeLayout() {
        Path logs = AntigravityPaths.logsDir("codex", "abc");
        assertTrue(
            logs.endsWith(
                Paths.get(".gemini", "codex", "brain", "abc", ".system_generated", "logs")
            )
        );
    }

    @Test
    void fileHelpersResolveTheExpectedNames() {
        Path logs = Paths.get("/logs");
        assertEquals(
            "transcript.jsonl",
            AntigravityPaths.transcript(logs).getFileName().toString()
        );
        assertEquals(
            "transcript_full.jsonl",
            AntigravityPaths.transcriptFull(logs).getFileName().toString()
        );
        assertEquals("summary.json", AntigravityPaths.summaryJson(logs).getFileName().toString());
        assertEquals("short_title.txt", AntigravityPaths.shortTitle(logs).getFileName().toString());
    }
}
