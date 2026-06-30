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
 * Turns the structural evidence mined by {@link PatternMiner} (recurring tool sequences, failure→fix
 * pairs, and recommendations across many sessions) into concrete, reusable assets: skills, AGENTS.md
 * rules, and tooling gaps. Uses the same shared {@code ChatModel} as {@link AnalyzerService}.
 */
@AiService
public interface MinerAdvisorService {
    @SystemMessage("""
        You are an expert at turning observed AI-agent session patterns into reusable engineering assets.
        You are given evidence aggregated across MANY sessions of one coding agent: recurring tool-call
        sequences (candidate workflows), recurring failure→fix pairs, and recommendations from prior
        analyses. Propose durable improvements that would make future sessions faster and more reliable.
        """)
    @UserMessage("""
        Below is structural evidence mined across many sessions.

        CRITICAL INSTRUCTIONS:
        - Ground EVERY proposal in the evidence. Do NOT invent patterns that are not present.
        - Prefer fewer, higher-confidence proposals over many speculative ones. It is fine to return empty lists.
        - `skills`: codify the recurring tool sequences into reusable workflows (name, whenToUse, numbered body).
        - `agentsRules`: turn recurring failure→fix pairs into durable, imperative AGENTS.md guidelines with a rationale.
        - `toolingGaps`: name missing tools or friction the failures imply, one short phrase each.
        - Be succinct. Keep each field to 1-2 sentences (the skill body may use short numbered steps).
        - Output MUST be exclusively in English. DO NOT output Base64 or repeat words. If you start repeating, STOP.

        Evidence:
        {{evidence}}
        """)
    MiningProposal propose(@V("evidence") String evidence);
}
