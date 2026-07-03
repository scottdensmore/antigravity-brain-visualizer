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
 * The optional LLM-judge layer of an {@link EvalReport}: average rubric scores across the judged
 * sessions and the per-case ratings. When {@link #ran()} is false, {@link #note()} explains why (not
 * requested, AI not configured, or the model was unavailable) and the averages/cases are empty.
 */
@Serdeable
public record JudgeSummary(
    boolean ran,
    String note,
    int judgedSessions,
    double avgFaithfulness,
    double avgActionability,
    double avgClarity,
    List<JudgedCase> cases
) {
    /** A "did not run" summary carrying only the explanatory note. */
    static JudgeSummary notRun(String note) {
        return new JudgeSummary(false, note, 0, 0.0, 0.0, 0.0, List.of());
    }
}
