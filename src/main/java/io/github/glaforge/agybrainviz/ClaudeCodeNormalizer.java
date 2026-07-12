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

import jakarta.inject.Singleton;
import java.util.List;
import java.util.Optional;

/** Normalizes a pushed Claude Code session, reusing {@link ClaudeCodeAdapter}. */
@Singleton
public class ClaudeCodeNormalizer implements SourceNormalizer {

    // The source value this normalizer handles; the frontend and CLI use the same string.
    private static final String FLAVOR = "claude-code";

    @Override
    public boolean handles(String source) {
        return FLAVOR.equals(source);
    }

    @Override
    public String toStepsJson(List<String> rawLines) {
        return ClaudeCodeAdapter.toTranscriptJson(rawLines);
    }

    @Override
    public Optional<String> deriveTitle(List<String> rawLines) {
        return ClaudeCodeAdapter.deriveSummary(rawLines);
    }
}
