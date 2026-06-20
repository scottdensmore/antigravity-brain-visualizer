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

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;

/**
 * Central configuration for the AI backend. Two providers are supported:
 *
 * <ul>
 *   <li>{@code GEMINI} (default) — the remote Google Gemini API, requiring {@code GEMINI_API_KEY}.
 *   <li>{@code OLLAMA} — a local model (e.g. Gemma) served by Ollama; no API key required.
 * </ul>
 *
 * <p>Selection and settings come from environment variables:
 *
 * <ul>
 *   <li>{@code AI_PROVIDER} — {@code gemini} (default) or {@code ollama}
 *   <li>{@code GEMINI_API_KEY}, {@code GEMINI_MODEL} (default {@code gemini-3.5-flash})
 *   <li>{@code OLLAMA_BASE_URL} (default {@code http://localhost:11434}),
 *       {@code OLLAMA_MODEL} (default {@code gemma4})
 * </ul>
 *
 * <p>Reading through this bean (rather than {@code System.getenv} inline) gives tests a seam: the
 * package-private constructor accepts explicit values without touching process environment.
 */
@Singleton
public class AiConfig {

    public enum Provider {
        GEMINI,
        OLLAMA,
    }

    static final String DEFAULT_GEMINI_MODEL = "gemini-3.5-flash";
    static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
    static final String DEFAULT_OLLAMA_MODEL = "gemma4";

    private final String providerName;
    private final String geminiApiKey;
    private final String geminiModel;
    private final String ollamaBaseUrl;
    private final String ollamaModel;

    @Inject
    public AiConfig() {
        this(
            env("AI_PROVIDER"),
            env("GEMINI_API_KEY"),
            envOr("GEMINI_MODEL", DEFAULT_GEMINI_MODEL),
            envOr("OLLAMA_BASE_URL", DEFAULT_OLLAMA_BASE_URL),
            envOr("OLLAMA_MODEL", DEFAULT_OLLAMA_MODEL)
        );
    }

    // Test seam: construct with explicit values, bypassing the environment.
    AiConfig(
        String providerName,
        String geminiApiKey,
        String geminiModel,
        String ollamaBaseUrl,
        String ollamaModel
    ) {
        this.providerName = providerName;
        this.geminiApiKey = geminiApiKey;
        this.geminiModel = blankTo(geminiModel, DEFAULT_GEMINI_MODEL);
        this.ollamaBaseUrl = blankTo(ollamaBaseUrl, DEFAULT_OLLAMA_BASE_URL);
        this.ollamaModel = blankTo(ollamaModel, DEFAULT_OLLAMA_MODEL);
    }

    public Provider provider() {
        return providerName != null && providerName.trim().equalsIgnoreCase("ollama")
            ? Provider.OLLAMA
            : Provider.GEMINI;
    }

    public Optional<String> geminiApiKey() {
        return (geminiApiKey == null || geminiApiKey.isBlank())
            ? Optional.empty()
            : Optional.of(geminiApiKey);
    }

    public String geminiModel() {
        return geminiModel;
    }

    public String ollamaBaseUrl() {
        return ollamaBaseUrl;
    }

    public String ollamaModel() {
        return ollamaModel;
    }

    /**
     * @return whether the selected provider has everything it needs to run. Ollama needs no key;
     *     Gemini requires {@code GEMINI_API_KEY}.
     */
    public boolean isConfigured() {
        return provider() == Provider.OLLAMA || geminiApiKey().isPresent();
    }

    /**
     * @return a user-facing message explaining why analysis cannot run when {@link #isConfigured()}
     *     is false.
     */
    public String notConfiguredMessage() {
        return "Error: GEMINI_API_KEY environment variable is not set. Cannot use LangChain4j analysis.";
    }

    private static String env(String key) {
        String value = System.getenv(key);
        return value == null ? "" : value;
    }

    private static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
