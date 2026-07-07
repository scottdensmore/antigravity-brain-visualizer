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
 * A persisted, lean snapshot of one eval run — enough to chart quality over time and A/B two runs
 * (e.g. before/after a model or prompt change) without storing the full per-session breakdown.
 *
 * @param savedAt ISO-8601 instant the run was saved (server-assigned; also its identity for display)
 */
@Serdeable
public record EvalRunSnapshot(
    String savedAt,
    String flavor,
    String modelLabel,
    int sessionCount,
    int evaluatedSessions,
    double avgScore,
    List<NameCount> checkPassRates,
    boolean judged,
    int judgedSessions,
    double avgFaithfulness,
    double avgActionability,
    double avgClarity
) {}
