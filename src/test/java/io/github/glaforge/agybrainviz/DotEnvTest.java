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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for the {@code .env} loader. */
class DotEnvTest {

    @Test
    void parsesKeyValuePairs() {
        Map<String, String> values = DotEnv.parse(
            List.of("AI_PROVIDER=ollama", "OLLAMA_MODEL=gemma4")
        );
        assertEquals("ollama", values.get("AI_PROVIDER"));
        assertEquals("gemma4", values.get("OLLAMA_MODEL"));
    }

    @Test
    void ignoresBlankLinesAndComments() {
        Map<String, String> values = DotEnv.parse(
            List.of("", "   ", "# a comment", "#KEY=ignored", "REAL=1")
        );
        assertEquals(1, values.size());
        assertEquals("1", values.get("REAL"));
    }

    @Test
    void toleratesExportPrefixAndSurroundingWhitespace() {
        Map<String, String> values = DotEnv.parse(
            List.of("export GEMINI_API_KEY=abc123", "  SPACED  =  value  ")
        );
        assertEquals("abc123", values.get("GEMINI_API_KEY"));
        assertEquals("value", values.get("SPACED"));
    }

    @Test
    void stripsQuotesAndKeepsQuotedSpecialCharacters() {
        Map<String, String> values = DotEnv.parse(
            List.of("A=\"a value # not a comment\"", "B='single'", "C=plain")
        );
        assertEquals("a value # not a comment", values.get("A"));
        assertEquals("single", values.get("B"));
        assertEquals("plain", values.get("C"));
    }

    @Test
    void dropsTrailingInlineCommentsOnUnquotedValues() {
        Map<String, String> values = DotEnv.parse(List.of("PORT=9090 # the server port"));
        assertEquals("9090", values.get("PORT"));
    }

    @Test
    void dropsTrailingCommentsAfterAQuotedValueWithoutKeepingTheQuotes() {
        // Regression: `KEY="secret" # note` must not yield the literal `"secret"` (a corrupted key).
        Map<String, String> values = DotEnv.parse(
            List.of("GEMINI_API_KEY=\"sk-secret\" # my key", "OTHER='single' # note", "EMPTY=\"\"")
        );
        assertEquals("sk-secret", values.get("GEMINI_API_KEY"));
        assertEquals("single", values.get("OTHER"));
        assertEquals("", values.get("EMPTY"));
    }

    @Test
    void toleratesATabAfterExportAndALeadingByteOrderMark() {
        Map<String, String> values = DotEnv.parse(
            List.of("﻿AI_PROVIDER=ollama", "export\tGEMINI_MODEL=fast")
        );
        assertEquals("ollama", values.get("AI_PROVIDER"));
        assertEquals("fast", values.get("GEMINI_MODEL"));
    }

    @Test
    void keepsValuesContainingEqualsSigns() {
        Map<String, String> values = DotEnv.parse(List.of("TOKEN=abc=def=="));
        assertEquals("abc=def==", values.get("TOKEN"));
    }

    @Test
    void skipsMalformedLines() {
        Map<String, String> values = DotEnv.parse(List.of("no-equals-sign", "=novalue", "OK=1"));
        assertEquals(1, values.size());
        assertEquals("1", values.get("OK"));
    }

    @Test
    void handlesNullAndEmptyInput() {
        assertTrue(DotEnv.parse(null).isEmpty());
        assertTrue(DotEnv.parse(List.of()).isEmpty());
    }

    @Test
    void loadsFromAFileAndYieldsNothingWhenMissing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".env");
        Files.writeString(file, "AI_PROVIDER=ollama\n# comment\n");
        assertEquals("ollama", DotEnv.load(file).get("AI_PROVIDER"));

        assertTrue(DotEnv.load(dir.resolve("absent.env")).isEmpty());
        // A directory is not a regular file: no values, no exception.
        assertTrue(DotEnv.load(dir).isEmpty());
    }

    @Test
    void isDisabledForTestsSoALocalEnvFileCannotLeakIn() {
        // build.gradle sets -Ddotenv.enabled=false for all test tasks.
        assertFalse(DotEnv.enabled());
        assertTrue(DotEnv.values().isEmpty());
        assertNull(DotEnv.get("GEMINI_API_KEY"));
    }
}
