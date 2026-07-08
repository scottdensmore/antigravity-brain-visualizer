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

/**
 * One session's ensembled LLM-judge result: the panel's average rubric scores (1-5, possibly
 * fractional), how many panel verdicts contributed ({@code samples}), the panel's overall-score
 * spread ({@code panelMin}/{@code panelMax}, so disagreement is visible), and one representative
 * comment.
 */
@Serdeable
public record JudgedCase(
    String sessionId,
    String title,
    double faithfulness,
    double actionability,
    double clarity,
    int samples,
    double panelMin,
    double panelMax,
    String comment
) {}
