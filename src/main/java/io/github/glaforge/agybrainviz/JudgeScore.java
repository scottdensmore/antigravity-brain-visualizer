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

/** An LLM judge's 1-5 rubric rating of a single analysis, with a one-line justification. */
@ReflectiveAccess
@Serdeable
public record JudgeScore(
    @Description(
        "How faithfully the analysis reflects what actually happened in the transcript, 1 (poor) to 5 (excellent)."
    )
    int faithfulness,
    @Description("How specific and actionable the issues and recommendations are, 1 to 5.")
    int actionability,
    @Description("How clear and concise the analysis is, 1 to 5.") int clarity,
    @Description("One short sentence justifying the scores. MAX 1 SENTENCE.") String comment
) {}
