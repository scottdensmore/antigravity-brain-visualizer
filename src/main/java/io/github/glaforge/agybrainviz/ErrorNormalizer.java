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

import java.util.regex.Pattern;

/**
 * Collapses a raw error blob into a stable, human-readable grouping key so that near-identical
 * failures cluster in the "most common errors" tally instead of each getting its own bucket.
 *
 * <p>Raw tool errors from Codex and Claude Code (unlike Antigravity's terse step errors) are full
 * stderr/stack-trace dumps whose only difference between two runs of the same underlying failure is
 * volatile detail — absolute file paths, line numbers, PIDs, ports, hex addresses, UUIDs, and the
 * specific quoted value. This picks the most salient line of the blob and canonicalises those
 * volatile tokens to placeholders, so e.g. two {@code ENOENT ... open '/a/x.txt'} /
 * {@code ... open '/b/y.txt'} failures both normalise to {@code ... open '<v>'}.
 */
final class ErrorNormalizer {

    private ErrorNormalizer() {}

    private static final int MAX = 100;
    /** Cap the salient line before canonicalising, so regex never scans a huge single-line blob. */
    private static final int MAX_SCAN = 500;
    private static final String STEP_PREFIX = "Encountered error in step execution: ";

    // Lines whose lower-case form contains one of these are a strong error signature — preferred.
    private static final String[] STRONG = {
        "error",
        "exception",
        "cannot",
        "no such file",
        "not found",
        "permission denied",
        "traceback",
        "panic",
        "fatal",
        "segmentation fault",
        "npm err",
    };
    // Weaker signals (a bare exit line) — used only if no strong line is present.
    private static final String[] WEAK = { "failed", "exited with code", "non-zero exit" };

    private static final Pattern HEX = Pattern.compile("\\b0x[0-9a-fA-F]+\\b");
    private static final Pattern UUID = Pattern.compile(
        "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"
    );
    private static final Pattern SINGLE_QUOTED = Pattern.compile("'[^']*'");
    private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"[^\"]*\"");
    // A slash-bearing token: at least one path separator between name segments (POSIX or Windows).
    private static final Pattern PATH = Pattern.compile(
        "(?:[A-Za-z]:)?(?:[\\w.+\\-]*[/\\\\])+[\\w.+\\-]+"
    );
    private static final Pattern NUMBER = Pattern.compile("\\b\\d+\\b");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * @param raw the raw error text (a step's {@code error} or {@code content})
     * @return a normalised, truncated grouping key, or {@code "Unknown error"} when there is nothing
     *     meaningful to key on
     */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return "Unknown error";

        String line = salientLine(raw);
        if (line == null) return "Unknown error";
        if (line.startsWith(STEP_PREFIX)) line = line.substring(STEP_PREFIX.length()).strip();
        // Bound the regex work: a single tool-output line can be huge, and we only key on its head.
        if (line.length() > MAX_SCAN) line = line.substring(0, MAX_SCAN);

        String key = canonicalize(line).strip();
        if (key.isBlank()) return "Unknown error";
        return key.length() > MAX ? key.substring(0, MAX) : key;
    }

    /** Picks the most informative line: the first strong error line, else a weak one, else the first. */
    private static String salientLine(String raw) {
        String firstMeaningful = null;
        String weak = null;
        for (String rawLine : raw.split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty() || isMetadata(line)) continue;
            if (firstMeaningful == null) firstMeaningful = line;
            String lower = line.toLowerCase();
            if (containsAny(lower, STRONG)) return line;
            if (weak == null && containsAny(lower, WEAK)) weak = line;
        }
        return weak != null ? weak : firstMeaningful;
    }

    private static boolean isMetadata(String line) {
        return line.startsWith("Created At:") || line.startsWith("Completed At:");
    }

    private static boolean containsAny(String lower, String[] needles) {
        for (String needle : needles) {
            if (lower.contains(needle)) return true;
        }
        return false;
    }

    private static String canonicalize(String s) {
        s = HEX.matcher(s).replaceAll("<hex>");
        s = UUID.matcher(s).replaceAll("<id>");
        // Quoted values first, so a quoted path collapses to '<v>' rather than '<path>'.
        s = SINGLE_QUOTED.matcher(s).replaceAll("'<v>'");
        s = DOUBLE_QUOTED.matcher(s).replaceAll("\"<v>\"");
        s = PATH.matcher(s).replaceAll("<path>");
        s = NUMBER.matcher(s).replaceAll("<n>");
        s = WHITESPACE.matcher(s).replaceAll(" ");
        return s;
    }
}
