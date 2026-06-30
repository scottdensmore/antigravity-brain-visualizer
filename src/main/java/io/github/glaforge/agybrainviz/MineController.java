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

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Optional;

/** Mines reusable skills and AGENTS.md rules from a source's sessions. */
@Controller("/api/mine")
public class MineController {

    private final MinerService minerService;

    @Inject
    public MineController(MinerService minerService) {
        this.minerService = minerService;
    }

    @ExecuteOn(TaskExecutors.IO)
    @Get(produces = "application/json")
    public MiningReport mine(@QueryValue Optional<String> flavor) throws IOException {
        return minerService.forFlavor(flavor.orElse("antigravity-cli"));
    }
}
