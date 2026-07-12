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

import java.util.List;
import java.util.Optional;

/**
 * Converts one agent tool's own transcript into the shared timeline-step schema.
 *
 * <p>Ingest clients (the {@code agent-ingest} CLI) push a session's <em>raw</em> lines, exactly as the
 * tool wrote them, and the server normalizes. Keeping the schema knowledge here rather than in the
 * client means a new client — or one written in another language — never has to reimplement
 * {@link CodexAdapter} and friends, and an adapter fix reaches every machine by upgrading the server
 * alone.
 *
 * <p>Implementations are {@code @Singleton} beans, collected as a {@code List<SourceNormalizer>} and
 * selected by {@link #handles(String)}.
 */
public interface SourceNormalizer {
    /**
     * @param source the {@code source} value the client pushed (the frontend's {@code flavor})
     * @return whether this normalizer serves that source
     */
    boolean handles(String source);

    /**
     * @param rawLines the tool's own transcript, one JSON object per line
     * @return the normalized timeline as a JSON array string; {@code "[]"} when nothing is renderable
     */
    String toStepsJson(List<String> rawLines);

    /**
     * @return a short title for the session list, when the transcript reveals one. The client may send
     *     its own; this is the fallback, and is what makes a title consistent across machines.
     */
    Optional<String> deriveTitle(List<String> rawLines);
}
