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

import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.google.genai.GoogleGenAiTokenCountEstimator;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Estimates the number of tokens in a piece of text using the Google GenAI tokenizer.
 *
 * <p>The underlying {@link TokenCountEstimator} is built lazily on first use so that constructing
 * the bean (e.g. at application startup) never performs network I/O. Wrapping the estimator in a
 * bean lets tests replace token counting with a deterministic stub.
 *
 * <p>Because the estimator is built on first {@link #estimate(String)} call — which the analysis
 * pipeline invokes inside a try/catch that falls back to a character-length heuristic — a failure to
 * build the estimator (or to estimate) degrades to that heuristic rather than aborting the analysis.
 */
@Singleton
public class TokenCounter {

    private final GeminiConfig config;
    private volatile TokenCountEstimator estimator;

    @Inject
    public TokenCounter(GeminiConfig config) {
        this.config = config;
    }

    /**
     * @param text the text to measure
     * @return the estimated token count for {@code text}
     */
    public int estimate(String text) {
        return delegate().estimateTokenCountInText(text);
    }

    private TokenCountEstimator delegate() {
        TokenCountEstimator local = estimator;
        if (local == null) {
            synchronized (this) {
                local = estimator;
                if (local == null) {
                    local =
                        GoogleGenAiTokenCountEstimator
                            .builder()
                            .apiKey(config.apiKey().orElse("dummy"))
                            .modelName("gemini-3.5-flash")
                            .build();
                    estimator = local;
                }
            }
        }
        return local;
    }
}
