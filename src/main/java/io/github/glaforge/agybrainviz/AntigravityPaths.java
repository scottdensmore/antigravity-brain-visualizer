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

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The single source of truth for Antigravity's on-disk layout under the user's home. Antigravity
 * stores each flavor's sessions in {@code ~/.gemini/<flavor>/brain/<id>/.system_generated/logs/},
 * and the previous scattering of these literals across the brain controller, analysis controller,
 * and session collector was the duplication this consolidates.
 *
 * <p>{@code user.home} is read on every call (not cached) so tests that redirect it are honoured.
 */
final class AntigravityPaths {

    private AntigravityPaths() {}

    /** The flavor used when none is supplied. */
    static final String DEFAULT_FLAVOR = "antigravity-cli";

    private static final String LOGS_DIR = ".system_generated";
    private static final String LOGS_SUBDIR = "logs";
    private static final String TRANSCRIPT = "transcript.jsonl";
    private static final String TRANSCRIPT_FULL = "transcript_full.jsonl";
    private static final String SUMMARY_JSON = "summary.json";
    private static final String SHORT_TITLE = "short_title.txt";

    /** The {@code ~/.gemini} root (used both for brain lookups and file-access sandboxing). */
    static Path geminiRoot() {
        return Paths.get(System.getProperty("user.home"), ".gemini");
    }

    /** The {@code brain} directory for a flavor; a null/blank flavor falls back to the default. */
    static Path brainDir(String flavor) {
        String dir = (flavor == null || flavor.isEmpty()) ? DEFAULT_FLAVOR : flavor;
        return geminiRoot().resolve(dir).resolve("brain");
    }

    /** The generated-logs directory inside a given session directory. */
    static Path logsDir(Path sessionDir) {
        return sessionDir.resolve(LOGS_DIR).resolve(LOGS_SUBDIR);
    }

    /** The generated-logs directory for a session id under a flavor's brain. */
    static Path logsDir(String flavor, String id) {
        return logsDir(brainDir(flavor).resolve(id));
    }

    static Path transcript(Path logsDir) {
        return logsDir.resolve(TRANSCRIPT);
    }

    static Path transcriptFull(Path logsDir) {
        return logsDir.resolve(TRANSCRIPT_FULL);
    }

    static Path summaryJson(Path logsDir) {
        return logsDir.resolve(SUMMARY_JSON);
    }

    static Path shortTitle(Path logsDir) {
        return logsDir.resolve(SHORT_TITLE);
    }
}
