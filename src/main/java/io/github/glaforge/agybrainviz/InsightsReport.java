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
 * Aggregate, cross-session analytics for one source (flavor): how many sessions, how often they hit
 * errors, the busiest tools, the most common failures, and a rolled-up backlog of the
 * recommendations and issues surfaced by the per-session AI analyses.
 *
 * @param sampledSessions how many sessions were actually scanned (the scan is capped for
 *     responsiveness; see {@code InsightsService})
 */
@Serdeable
public record InsightsReport(
    String flavor,
    int sessionCount,
    int sampledSessions,
    int analyzedSessions,
    int toolCallTotal,
    int sessionsWithErrors,
    int cleanSessions,
    double avgToolsPerSession,
    double avgDurationSeconds,
    List<NameCount> topTools,
    List<NameCount> topErrors,
    List<NameCount> topRecommendations,
    List<NameCount> topIssues
) {}
