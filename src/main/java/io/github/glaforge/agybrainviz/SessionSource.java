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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A non-Antigravity transcript source (e.g. OpenAI Codex, Claude Code). Implementations adapt a
 * tool's own session files into the timeline-step schema the frontend renders and provide an
 * analysis cache, so both {@link BrainController} and {@link AnalysisController} can treat every
 * source uniformly via a registry rather than per-flavor branching.
 */
public interface SessionSource {
    /**
     * @param flavor the {@code flavor} selector value sent by the frontend
     * @return whether this source serves that flavor
     */
    boolean handles(String flavor);

    /** @return one entry per session ({@code id}, {@code summary}, {@code updatedAt}), newest first. */
    List<Map<String, String>> listConversations();

    /** @return the session's timeline as a JSON array string, or {@code "[]"} if not found. */
    String transcriptJson(String id) throws IOException;

    /** @return whether a session with this id exists. */
    boolean sessionExists(String id) throws IOException;

    /** @return the condensed analysis input (one list of lines per sequence) for a session. */
    List<List<String>> analysisSequences(String id) throws IOException;

    /** @return the cached analysis summary JSON for a session, if present. */
    Optional<String> cachedSummary(String id) throws IOException;

    /** Deletes any cached summary/title for a session (used on forced recompute). */
    void deleteCache(String id) throws IOException;

    /** Writes the computed summary JSON (and optional short title) to the cache. */
    void writeCache(String id, String summaryJson, String title) throws IOException;
}
