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

import jakarta.inject.Singleton;
import java.util.Optional;

/**
 * Injectable accessor for the Gemini API key. Reading the key through a bean (rather than calling
 * {@code System.getenv} inline) gives tests a seam to supply a deterministic value without touching
 * process environment variables.
 */
@Singleton
public class GeminiConfig {

    /**
     * @return the configured Gemini API key, or {@link Optional#empty()} when the
     *     {@code GEMINI_API_KEY} environment variable is unset or blank.
     */
    public Optional<String> apiKey() {
        String key = System.getenv("GEMINI_API_KEY");
        if (key == null || key.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(key);
    }
}
