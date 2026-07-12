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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * Gathers a source's sessions — each session's normalized timeline steps plus any cached AI analysis
 * — for the cross-session features ({@link InsightsService}, {@code MinerService},
 * {@link EvalService}, {@code OptimizeService}), reading them from the shared store.
 *
 * <p>The scan is capped at the most recent {@link #MAX_SESSIONS} sessions so callers stay responsive
 * even for very large histories; {@link Collected#totalSessionCount()} still reports the true total.
 */
@Singleton
public class SessionCollector {

    static final int MAX_SESSIONS = 150;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SessionRepository sessions;

    @Inject
    public SessionCollector(SessionRepository sessions) {
        this.sessions = sessions;
    }

    /** The gathered sessions for a flavor and the true total (which may exceed the sampled size). */
    public record Collected(int totalSessionCount, List<FleetInsights.Session> sessions) {}

    public Collected collect(String flavor) {
        int total = sessions.countBySource(flavor);
        List<FleetInsights.Session> gathered = new ArrayList<>();
        for (SessionRepository.CollectedSession row : sessions.collect(flavor, MAX_SESSIONS)) {
            JsonNode summary = row.summaryJson() == null ? null : parseJson(row.summaryJson());
            gathered.add(new FleetInsights.Session(row.id(), parseArray(row.stepsJson()), summary));
        }
        return new Collected(total, gathered);
    }

    private List<JsonNode> parseArray(String jsonArray) {
        List<JsonNode> steps = new ArrayList<>();
        try {
            JsonNode arr = MAPPER.readTree(jsonArray);
            if (arr.isArray()) arr.forEach(steps::add);
        } catch (Exception e) {
            // ignore malformed transcript
        }
        return steps;
    }

    private JsonNode parseJson(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
