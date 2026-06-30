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

/** A proposed reusable skill (a codified, recurring workflow) ready to drop into a project. */
@ReflectiveAccess
@Serdeable
public record SkillProposal(
    @Description("A short kebab-case skill name. MAX 4 WORDS.") String name,
    @Description(
        "When this skill should be used, grounded in the observed sequences. MAX 1 SENTENCE."
    )
    String whenToUse,
    @Description(
        "The skill body: concrete, numbered steps the agent should follow. Plain text, no code fences."
    )
    String body
) {}
