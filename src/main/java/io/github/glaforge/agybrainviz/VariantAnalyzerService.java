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

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.micronaut.langchain4j.annotation.AiService;

/**
 * Analyzes a transcript with a caller-supplied instruction, so the "prompt lab" ({@code
 * OptimizeService}) can compare two prompt variants against the same input. Single-method by design
 * (unlike {@link AnalyzerService}) so it's trivially faked in tests; uses the shared {@code
 * ChatModel}.
 */
@AiService
public interface VariantAnalyzerService {
    @UserMessage("""
        {{instruction}}

        CRITICAL: Be succinct — 1 to 2 sentences per item, no repeated words or phrases, no Base64.
        Output MUST be exclusively in English.

        Transcript:
        {{transcript}}
        """)
    AnalysisResponse analyzeWithInstruction(
        @V("instruction") String instruction,
        @V("transcript") String transcript
    );
}
