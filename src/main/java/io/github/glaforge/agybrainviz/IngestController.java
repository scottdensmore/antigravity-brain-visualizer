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
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;

/**
 * Receives trajectories pushed by an ingest client, such as the {@code agent-ingest} CLI.
 *
 * <p>The app is the only thing that talks to the database, so a client on any machine — in any
 * language — can contribute trajectories by calling these two endpoints. Guarded by
 * {@link IngestAuthFilter} when {@code INGEST_TOKEN} is set.
 */
@Controller("/api/ingest")
public class IngestController {

    private final Ingestor ingestor;
    private final SessionRepository sessions;

    @Inject
    public IngestController(Ingestor ingestor, SessionRepository sessions) {
        this.ingestor = ingestor;
        this.sessions = sessions;
    }

    /**
     * Every stored {@code id -> contentHash} for a source.
     *
     * <p>A client fetches this first and pushes only what is missing or changed, so a routine sync
     * uploads nothing and a large corpus stays cheap to keep in step.
     */
    @ExecuteOn(TaskExecutors.IO)
    @Get(value = "/manifest", produces = "application/json")
    public Map<String, String> manifest(@QueryValue String source) {
        return sessions.manifest(source);
    }

    /** Normalizes and stores a batch of pushed trajectories. Re-pushing an unchanged one is a no-op. */
    @ExecuteOn(TaskExecutors.IO)
    @Post(value = "/sessions", produces = "application/json")
    public IngestResult push(@Body List<IngestSession> sessions) {
        return ingestor.ingest(sessions);
    }
}
