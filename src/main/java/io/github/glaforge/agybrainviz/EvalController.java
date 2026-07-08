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
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Scores the quality of a source's cached AI analyses and persists run history for comparison. */
@Controller("/api/eval")
public class EvalController {

    private final EvalService evalService;
    private final EvalRunStore runStore;

    @Inject
    public EvalController(EvalService evalService, EvalRunStore runStore) {
        this.evalService = evalService;
        this.runStore = runStore;
    }

    @ExecuteOn(TaskExecutors.IO)
    @Get(produces = "application/json")
    public EvalReport eval(
        @QueryValue Optional<String> flavor,
        @QueryValue Optional<Boolean> judge
    ) throws IOException {
        return evalService.forFlavor(flavor.orElse("antigravity-cli"), judge.orElse(false));
    }

    /** Saves a snapshot of a completed eval run so it can be compared against later runs. */
    @ExecuteOn(TaskExecutors.IO)
    @Post(value = "/runs", produces = "application/json")
    public EvalRunSnapshot saveRun(@Body EvalReport report) throws IOException {
        return runStore.save(report);
    }

    /** The saved run history for a flavor, newest first. */
    @ExecuteOn(TaskExecutors.IO)
    @Get(value = "/runs", produces = "application/json")
    public List<EvalRunSnapshot> listRuns(@QueryValue Optional<String> flavor) throws IOException {
        return runStore.list(flavor.orElse("antigravity-cli"));
    }

    /** Deletes the saved run identified by its {@code savedAt} timestamp. */
    @ExecuteOn(TaskExecutors.IO)
    @Delete(value = "/runs", produces = "application/json")
    public DeleteResult deleteRun(@QueryValue String savedAt) throws IOException {
        return new DeleteResult(runStore.delete(savedAt));
    }
}
