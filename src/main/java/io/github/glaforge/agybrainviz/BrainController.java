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

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Serves the session list and transcripts from the shared store, keyed by {@code flavor}/source. */
@Controller("/api/brain")
public class BrainController {

    private final SessionRepository sessions;

    @Inject
    public BrainController(SessionRepository sessions) {
        this.sessions = sessions;
    }

    @ExecuteOn(TaskExecutors.IO)
    @Get("/conversations")
    public List<Map<String, String>> listConversations(@QueryValue Optional<String> flavor) {
        return sessions.listConversations(flavor.orElse(AntigravityPaths.DEFAULT_FLAVOR));
    }

    @ExecuteOn(TaskExecutors.IO)
    @Get(value = "/conversations/{id}/transcript", produces = "application/json")
    public String getTranscript(@PathVariable String id, @QueryValue Optional<String> flavor) {
        String steps = sessions.steps(flavor.orElse(AntigravityPaths.DEFAULT_FLAVOR), id);
        return steps != null ? steps : "[]";
    }

    /**
     * Serves a referenced file's contents for the inline preview.
     *
     * <p>This one endpoint is inherently machine-local: Antigravity transcripts link to files under
     * the user's {@code ~/.gemini}, which live on the machine that ran the agent, not in the store. On
     * a different machine the file simply isn't there, and the preview returns not-found — the one
     * honest exception to reads coming from the store.
     */
    @ExecuteOn(TaskExecutors.IO)
    @Get(value = "/file", produces = "text/plain")
    public HttpResponse<String> getFileContent(@QueryValue String path) {
        try {
            Path filePath = Paths.get(path).normalize();
            Path geminiDir = AntigravityPaths.geminiRoot().normalize();
            if (!filePath.startsWith(geminiDir)) {
                return HttpResponse.unauthorized();
            }
            if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
                return HttpResponse.ok(Files.readString(filePath));
            } else {
                return HttpResponse.notFound("File not available on this machine.");
            }
        } catch (IOException e) {
            return HttpResponse.serverError("Error reading file: " + e.getMessage());
        }
    }
}
