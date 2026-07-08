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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads configuration from a {@code .env} file in the process's <em>current working directory</em>, so
 * the app can be configured without exporting shell variables (works for {@code gradlew run}, the fat
 * jar, and the native executable alike).
 *
 * <p>Real environment variables always take precedence over the file: callers consult
 * {@code System.getenv} first and fall back to {@link #get(String)}. Loading can be turned off with
 * {@code -Ddotenv.enabled=false} (the test and e2e runs do this, so a developer's local {@code .env}
 * can never change test behaviour), and the path overridden with {@code -Ddotenv.path=...}.
 *
 * <p>Format: {@code KEY=VALUE} per line. Blank lines and {@code #} comment lines are ignored, an
 * {@code export } prefix is tolerated, surrounding single/double quotes are stripped, and for
 * unquoted values a trailing {@code # comment} is dropped.
 */
final class DotEnv {

    private DotEnv() {}

    private static final String ENABLED_PROPERTY = "dotenv.enabled";
    private static final String PATH_PROPERTY = "dotenv.path";
    private static final Pattern EXPORT_PREFIX = Pattern.compile("^export\\s+");

    private static volatile Map<String, String> cached;

    /** Whether {@code .env} loading is active (default true; disabled in tests via a system property). */
    static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY, "true"));
    }

    /** The parsed {@code .env} values, loaded once. Empty when disabled or the file is absent. */
    static Map<String, String> values() {
        Map<String, String> local = cached;
        if (local == null) {
            synchronized (DotEnv.class) {
                local = cached;
                if (local == null) {
                    local = Collections.unmodifiableMap(enabled() ? loadDefault() : Map.of());
                    cached = local;
                }
            }
        }
        return local;
    }

    /** @return the {@code .env} value for {@code key}, or {@code null} when unset. */
    static String get(String key) {
        return values().get(key);
    }

    /** Loads the default file, tolerating a malformed {@code -Ddotenv.path} rather than crashing. */
    private static Map<String, String> loadDefault() {
        try {
            return load(defaultPath());
        } catch (RuntimeException e) {
            return Map.of(); // e.g. an invalid dotenv.path
        }
    }

    private static Path defaultPath() {
        String override = System.getProperty(PATH_PROPERTY);
        return (override == null || override.isBlank()) ? Paths.get(".env") : Paths.get(override);
    }

    /** Reads and parses a {@code .env} file; a missing or unreadable file yields no values. */
    static Map<String, String> load(Path file) {
        try {
            return Files.isRegularFile(file) ? parse(Files.readAllLines(file)) : Map.of();
        } catch (IOException e) {
            return Map.of();
        }
    }

    /** Parses {@code KEY=VALUE} lines. See the class docs for the accepted format. */
    static Map<String, String> parse(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        if (lines == null) return values;
        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.strip();
            // A UTF-8 BOM isn't whitespace, so it would otherwise become part of the first key.
            if (!line.isEmpty() && line.charAt(0) == '﻿') line = line.substring(1).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            line = EXPORT_PREFIX.matcher(line).replaceFirst("");

            int equals = line.indexOf('=');
            if (equals <= 0) continue; // no key, or no '=' at all
            String key = line.substring(0, equals).strip();
            if (key.isEmpty()) continue;
            values.put(key, cleanValue(line.substring(equals + 1).strip()));
        }
        return values;
    }

    private static String cleanValue(String value) {
        if (!value.isEmpty()) {
            char quote = value.charAt(0);
            if (quote == '"' || quote == '\'') {
                int close = value.indexOf(quote, 1);
                // A quoted value is taken verbatim (it may contain '#' or spaces); anything after the
                // closing quote — typically an inline comment — is discarded.
                if (close > 0) return value.substring(1, close);
            }
        }
        // Unquoted: drop a trailing inline comment.
        int comment = value.indexOf(" #");
        return (comment >= 0 ? value.substring(0, comment) : value).strip();
    }
}
