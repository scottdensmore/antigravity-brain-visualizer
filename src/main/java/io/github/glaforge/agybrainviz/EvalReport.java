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
 * The analysis-quality scoreboard for one source: how many of its cached analyses were graded, their
 * average score, how often each named check passed, and the lowest-scoring cases to look at first.
 *
 * @param modelLabel the AI provider/model currently configured, so two runs (e.g. after switching
 *     model or prompt and recomputing analyses) can be compared apples-to-apples
 * @param checkPassRates for each named check, how many of the evaluated sessions passed it
 */
@Serdeable
public record EvalReport(
    String flavor,
    int sessionCount,
    int sampledSessions,
    int evaluatedSessions,
    double avgScore,
    String modelLabel,
    List<NameCount> checkPassRates,
    List<EvalCaseResult> worstCases
) {}
