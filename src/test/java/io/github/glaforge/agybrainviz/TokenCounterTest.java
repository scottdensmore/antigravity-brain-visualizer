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

import org.junit.jupiter.api.Test;

/**
 * Tests the Ollama token-estimation path, which must be a purely local character heuristic (no
 * network, no remote tokenizer). The Gemini path delegates to the Google tokenizer and is covered by
 * the integration tests with a mocked TokenCounter.
 */
class TokenCounterTest {

    private TokenCounter ollamaCounter() {
        return new TokenCounter(new AiConfig("ollama", "", "", "", ""));
    }

    @Test
    void ollamaUsesALocalCharacterHeuristic() {
        // ~4 characters per token.
        assertEquals(2, ollamaCounter().estimate("abcdefgh"));
        assertEquals(25, ollamaCounter().estimate("x".repeat(100)));
    }

    @Test
    void ollamaEstimateIsAtLeastOne() {
        assertEquals(1, ollamaCounter().estimate("x"));
        assertEquals(1, ollamaCounter().estimate(""));
    }
}
