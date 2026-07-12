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
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Optional;

/**
 * Normalizes a pushed Antigravity transcript.
 *
 * <p>Antigravity's own {@code transcript.jsonl} schema already <em>is</em> the timeline-step schema
 * the frontend renders — it is the schema the other adapters translate <em>into</em> — so there is
 * nothing to convert. Lines are only parsed, which additionally drops any malformed line rather than
 * storing a transcript that would not parse back.
 *
 * <p>Serves every Antigravity flavor ({@code antigravity-cli}, {@code antigravity-ide}, ...), each of
 * which is stored under its own {@code source}.
 */
@Singleton
public class AntigravityNormalizer implements SourceNormalizer {

    /** Every Antigravity flavor shares this prefix and this transcript schema. */
    static final String FLAVOR_PREFIX = "antigravity";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TITLE_MAX = 80;
    private static final String REQUEST_CLOSE = "</USER_REQUEST>";

    @Override
    public boolean handles(String source) {
        return source != null && source.startsWith(FLAVOR_PREFIX);
    }

    @Override
    public String toStepsJson(List<String> rawLines) {
        ArrayNode steps = MAPPER.createArrayNode();
        for (String line : rawLines) {
            if (line == null || line.isBlank()) continue;
            try {
                steps.add(MAPPER.readTree(line));
            } catch (Exception e) {
                // Skip a malformed line rather than poison the whole transcript.
            }
        }
        return steps.toString();
    }

    /** The first user prompt, with Antigravity's {@code <USER_REQUEST>} wrapper stripped. */
    @Override
    public Optional<String> deriveTitle(List<String> rawLines) {
        for (String line : rawLines) {
            if (line == null || line.isBlank()) continue;
            try {
                JsonNode step = MAPPER.readTree(line);
                if (!"USER_INPUT".equals(step.path("type").asText(""))) continue;
                String content = unwrapRequest(step.path("content").asText(""));
                if (!content.isEmpty()) return Optional.of(truncate(content));
            } catch (Exception e) {
                // Skip a malformed line.
            }
        }
        return Optional.empty();
    }

    private static String unwrapRequest(String content) {
        String text = content.replaceAll("(?s)<USER_REQUEST>\\s*", "");
        int end = text.indexOf(REQUEST_CLOSE);
        if (end != -1) text = text.substring(0, end);
        return text.trim();
    }

    private static String truncate(String text) {
        return text.length() > TITLE_MAX ? text.substring(0, TITLE_MAX) + "..." : text;
    }
}
