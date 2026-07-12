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
 * A cached AI analysis pushed on its own, for a session whose transcript is already stored.
 *
 * <p>A summary is normally uploaded with its transcript. But a tool can write its summary after the
 * transcript is final (Antigravity's {@code summary.json}), and the transcript then never changes —
 * so its ride-along never happens. This carries just the summary, keyed to the stored session, so it
 * can sync without re-sending the transcript.
 *
 * @param source the session's source ({@code antigravity-cli}, …)
 * @param id the session's stable id
 * @param summary the cached {@code AnalysisResponse} JSON to store
 */
@Serdeable
public record IngestSummary(String source, String id, String summary) {}
