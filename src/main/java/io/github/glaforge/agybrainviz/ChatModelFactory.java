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

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.google.genai.GoogleGenAiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;

/**
 * Produces the {@link ChatModel} used by {@link AnalyzerService}, choosing between the remote Google
 * Gemini API and a local Ollama model based on {@link AiConfig#provider()}.
 */
@Factory
public class ChatModelFactory {

    private final AiConfig aiConfig;

    @Inject
    public ChatModelFactory(AiConfig aiConfig) {
        this.aiConfig = aiConfig;
    }

    @Singleton
    public ChatModel chatModel() {
        if (aiConfig.provider() == AiConfig.Provider.OLLAMA) {
            return OllamaChatModel
                .builder()
                .baseUrl(aiConfig.ollamaBaseUrl())
                .modelName(aiConfig.ollamaModel())
                .temperature(0.0)
                // Disable "thinking": for reasoning-capable local models (e.g. Gemma) Ollama would
                // otherwise generate a long hidden chain-of-thought on every analysis call, which
                // can take minutes per request and makes the analysis appear to hang. We only need
                // the structured summary, not the reasoning.
                .think(false)
                .returnThinking(false)
                // Local models can be slower than the hosted API, so allow more time per request.
                .timeout(Duration.ofMinutes(5))
                .responseFormat(ResponseFormat.JSON)
                .build();
        }

        return GoogleGenAiChatModel
            .builder()
            .apiKey(aiConfig.geminiApiKey().orElse("dummy"))
            .modelName(aiConfig.geminiModel())
            .temperature(0.0)
            .maxRetries(0)
            .timeout(Duration.ofMinutes(2))
            .responseFormat(ResponseFormat.JSON)
            .build();
    }
}
