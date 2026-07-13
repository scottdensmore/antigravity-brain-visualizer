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
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Liveness and readiness probes, for a container orchestrator, load balancer, or uptime monitor.
 *
 * <p>Kept deliberately small rather than pulling in {@code micronaut-management}: a JDBC probe and a
 * flat JSON body are all that's needed, and it adds no reflective surface to the native image.
 *
 * <p>The two are distinct on purpose. Liveness answers "is the process up?" and never depends on the
 * database — the app is designed to boot and serve the UI even when the store is down, so failing
 * liveness on a database blip (and having an orchestrator kill the container) would be wrong.
 * Readiness answers "can it serve trajectory data right now?", which does require the store.
 */
@Controller("/health")
public class HealthController {

    private static final Logger LOG = LoggerFactory.getLogger(HealthController.class);

    /** Seconds to wait for the store to validate a connection before calling readiness down. */
    private static final int READINESS_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    @Inject
    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Liveness: the process is up and serving HTTP. Never touches the database.
     *
     * <p>Carries the build version so a deployed instance's version is one {@code curl /health} away —
     * handy for debugging which build is running (the CLI reports its own via {@code --version}).
     */
    @Get(produces = "application/json")
    public Map<String, String> liveness() {
        return Map.of("status", "UP", "version", Version.VERSION);
    }

    /** Readiness: UP only when the store answers, so traffic isn't routed to an app that can't serve. */
    @ExecuteOn(TaskExecutors.IO)
    @Get(value = "/ready", produces = "application/json")
    public HttpResponse<Map<String, String>> readiness() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(READINESS_TIMEOUT_SECONDS)) {
                return HttpResponse.ok(Map.of("status", "UP", "db", "UP"));
            }
        } catch (SQLException e) {
            LOG.debug("Readiness probe: the store is not reachable: {}", e.getMessage());
        }
        return HttpResponse
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("status", "DOWN", "db", "DOWN"));
    }
}
