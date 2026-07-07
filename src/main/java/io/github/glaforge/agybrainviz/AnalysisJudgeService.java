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

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.micronaut.langchain4j.annotation.AiService;

/**
 * An LLM-as-judge that rates one AI-generated session analysis against a digest of what actually
 * happened in the transcript. Complements the deterministic {@link EvalScorer} with subjective
 * rubric dimensions. Uses the same shared {@code ChatModel} as {@link AnalyzerService}.
 */
@AiService
public interface AnalysisJudgeService {
    @SystemMessage("""
        You are an impartial evaluator of AI-generated summaries of coding-agent sessions.
        Given a factual digest of what happened in a session (the user's requests, the tools the agent
        ran, and any errors) and the analysis that was produced about it, rate the analysis on a 1-5
        rubric. Judge ONLY against the digest; do not reward claims the digest does not support.
        """)
    @UserMessage("""
        Evaluate as {{lens}}.

        SESSION DIGEST (ground truth — what actually happened):
        {{digest}}

        ANALYSIS TO EVALUATE:
        {{analysis}}

        Rate the analysis:
        - faithfulness: does it accurately reflect the digest, without invented or contradicted claims?
        - actionability: are the issues and recommendations specific and useful?
        - clarity: is it clear and concise?
        Give an integer 1-5 for each and one short sentence of justification. Output English only.
        """)
    JudgeScore judge(
        @V("digest") String digest,
        @V("analysis") String analysis,
        @V("lens") String lens
    );
}
