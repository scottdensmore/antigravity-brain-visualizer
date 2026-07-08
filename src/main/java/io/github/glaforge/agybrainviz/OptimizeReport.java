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
 * The result of a prompt-lab comparison: how many sessions were sampled, an explanatory note when
 * the run couldn't proceed (AI not configured / no sessions), and each variant's eval outcome.
 */
@Serdeable
public record OptimizeReport(int sampleSize, String note, VariantResult a, VariantResult b) {
    static OptimizeReport unavailable(String note) {
        return new OptimizeReport(0, note, VariantResult.empty(), VariantResult.empty());
    }
}
