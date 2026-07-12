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

import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Inject;

/** The prompt lab: compares two analysis prompt variants, scored by the deterministic eval. */
@Controller("/api/optimize")
public class OptimizeController {

    private final OptimizeService optimizeService;

    @Inject
    public OptimizeController(OptimizeService optimizeService) {
        this.optimizeService = optimizeService;
    }

    /** The baseline instruction to seed the editors with. */
    @Serdeable
    public record DefaultInstruction(String instruction, int maxSample) {}

    @Get(produces = "application/json")
    public DefaultInstruction defaultInstruction() {
        return new DefaultInstruction(
            OptimizeService.DEFAULT_INSTRUCTION,
            OptimizeService.MAX_SAMPLE
        );
    }

    @ExecuteOn(TaskExecutors.IO)
    @Post(produces = "application/json")
    public OptimizeReport compare(@Body OptimizeRequest request) {
        String flavor = request.flavor() == null ? "antigravity-cli" : request.flavor();
        return optimizeService.compare(
            flavor,
            request.sampleSize(),
            request.instructionA(),
            request.instructionB()
        );
    }
}
