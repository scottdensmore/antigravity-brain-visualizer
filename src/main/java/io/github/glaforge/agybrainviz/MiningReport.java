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

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

/**
 * The output of the "skill / AGENTS.md miner" for one source: the structural evidence mined across
 * sessions plus, when AI is configured, the LLM-proposed skills, AGENTS.md rules, and tooling gaps.
 *
 * @param aiGenerated whether the LLM phrasing pass ran; when false, only the evidence is populated
 * @param note a short human-facing explanation when {@code aiGenerated} is false (e.g. AI not set up)
 */
@Serdeable
public record MiningReport(
    String flavor,
    int sessionCount,
    int sampledSessions,
    int analyzedSessions,
    boolean aiGenerated,
    String note,
    List<NameCount> toolSequences,
    List<FixPair> failureFixes,
    List<NameCount> recommendations,
    List<SkillProposal> skills,
    List<AgentsRule> agentsRules,
    List<String> toolingGaps
) {}
