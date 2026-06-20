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
 * Estimates the number of tokens in a piece of text, used to size analysis chunks.
 *
 * <p>For the Gemini provider this delegates to the Google GenAI tokenizer (built lazily on first use
 * so constructing the bean never performs network I/O). For the Ollama provider there is no cheap
 * remote tokenizer, so a character-based heuristic (~4 chars per token) is used — entirely local.
 *
 * <p>The Gemini estimator is built on first {@link #estimate(String)} call, which the analysis
 * pipeline invokes inside a try/catch that falls back to a character-length heuristic, so a failure
 * to build or estimate degrades gracefully rather than aborting the analysis.
 */
@Singleton
public class TokenCounter {

    private static final int APPROX_CHARS_PER_TOKEN = 4;

    private final AiConfig aiConfig;
    private volatile TokenCountEstimator estimator;

    @Inject
    public TokenCounter(AiConfig aiConfig) {
        this.aiConfig = aiConfig;
    }

    /**
     * @param text the text to measure
     * @return the estimated token count for {@code text}
     */
    public int estimate(String text) {
        if (aiConfig.provider() == AiConfig.Provider.OLLAMA) {
            return Math.max(1, text.length() / APPROX_CHARS_PER_TOKEN);
        }
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
                            .apiKey(aiConfig.geminiApiKey().orElse("dummy"))
                            .modelName(aiConfig.geminiModel())
                            .build();
                    estimator = local;
                }
            }
        }
        return local;
    }
}
