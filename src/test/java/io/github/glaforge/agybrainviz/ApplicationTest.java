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

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests how {@code .env} entries are mapped onto framework system properties. Uses a made-up
 * {@code MICRONAUT_FOO_*} namespace so real server settings are never touched by the test JVM.
 */
class ApplicationTest {

    private static final String KEY = "MICRONAUT_FOO_BAR";
    private static final String PROPERTY = "micronaut.foo.bar";

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void mapsMicronautKeysToDottedLowercaseProperties() {
        Application.applyFrameworkSettings(Map.of(KEY, "9090"));
        assertEquals("9090", System.getProperty(PROPERTY));
    }

    @Test
    void ignoresNonFrameworkKeys() {
        Application.applyFrameworkSettings(
            Map.of("GEMINI_API_KEY", "secret", "AI_PROVIDER", "ollama")
        );
        assertNull(System.getProperty("gemini.api.key"));
        assertNull(System.getProperty("ai.provider"));
    }

    @Test
    void anExplicitSystemPropertyWinsOverTheDotEnvFile() {
        System.setProperty(PROPERTY, "8080"); // as if passed with -D
        Application.applyFrameworkSettings(Map.of(KEY, "9090"));
        assertEquals("8080", System.getProperty(PROPERTY));
    }

    @Test
    void skipsBlankValuesRatherThanBreakingPropertyResolution() {
        Application.applyFrameworkSettings(Map.of(KEY, "   "));
        assertNull(System.getProperty(PROPERTY));
    }
}
