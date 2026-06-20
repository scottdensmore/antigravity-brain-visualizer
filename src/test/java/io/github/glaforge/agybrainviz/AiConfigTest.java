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

import io.github.glaforge.agybrainviz.AiConfig.Provider;
import org.junit.jupiter.api.Test;

class AiConfigTest {

    private AiConfig config(String provider, String key) {
        return new AiConfig(provider, key, null, null, null);
    }

    @Test
    void defaultsToGeminiWhenProviderUnset() {
        assertEquals(Provider.GEMINI, config(null, "k").provider());
        assertEquals(Provider.GEMINI, config("", "k").provider());
    }

    @Test
    void selectsOllamaCaseInsensitively() {
        assertEquals(Provider.OLLAMA, config("ollama", "").provider());
        assertEquals(Provider.OLLAMA, config("OLLAMA", "").provider());
        assertEquals(Provider.OLLAMA, config(" Ollama ", "").provider());
    }

    @Test
    void geminiIsConfiguredOnlyWithAKey() {
        assertFalse(config("gemini", "").isConfigured());
        assertFalse(config("gemini", "   ").isConfigured());
        assertTrue(config("gemini", "secret").isConfigured());
    }

    @Test
    void ollamaIsConfiguredWithoutAKey() {
        assertTrue(config("ollama", "").isConfigured());
        assertTrue(config("ollama", null).isConfigured());
    }

    @Test
    void notConfiguredMessageMentionsTheApiKey() {
        assertTrue(config("gemini", "").notConfiguredMessage().contains("GEMINI_API_KEY"));
    }

    @Test
    void appliesDefaultModelsAndUrlsWhenBlank() {
        AiConfig c = new AiConfig("ollama", "", "", "", "");
        assertEquals(AiConfig.DEFAULT_GEMINI_MODEL, c.geminiModel());
        assertEquals(AiConfig.DEFAULT_OLLAMA_BASE_URL, c.ollamaBaseUrl());
        assertEquals(AiConfig.DEFAULT_OLLAMA_MODEL, c.ollamaModel());
    }

    @Test
    void honorsExplicitModelsAndUrls() {
        AiConfig c = new AiConfig(
            "ollama",
            "",
            "gemini-custom",
            "http://remote:9999",
            "gemma3:27b"
        );
        assertEquals("gemini-custom", c.geminiModel());
        assertEquals("http://remote:9999", c.ollamaBaseUrl());
        assertEquals("gemma3:27b", c.ollamaModel());
    }

    @Test
    void exposesGeminiApiKeyOnlyWhenPresent() {
        assertTrue(config("gemini", "abc").geminiApiKey().isPresent());
        assertFalse(config("gemini", "").geminiApiKey().isPresent());
    }
}
