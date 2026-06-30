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

import dev.langchain4j.model.output.structured.Description;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

/** The LLM's proposed reusable assets, derived from the mined structural evidence. */
@ReflectiveAccess
@Serdeable
public record MiningProposal(
    @Description(
        "Reusable skills for the recurring workflows. Propose 0 to 5, only well-supported ones."
    )
    List<SkillProposal> skills,
    @Description("Durable AGENTS.md rules for the recurring failures. Propose 0 to 8.")
    List<AgentsRule> agentsRules,
    @Description(
        "Missing tools or friction points worth addressing, one short phrase each. Propose 0 to 6."
    )
    List<String> toolingGaps
) {}
