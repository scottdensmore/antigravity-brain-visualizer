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

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The Antigravity locations the app still needs after sessions moved to the store: the default flavor
 * and the {@code ~/.gemini} root that sandboxes the inline file-preview endpoint.
 *
 * <p>{@code user.home} is read on every call (not cached) so tests that redirect it are honoured.
 */
final class AntigravityPaths {

    private AntigravityPaths() {}

    /** The flavor used when none is supplied. */
    static final String DEFAULT_FLAVOR = "antigravity-cli";

    /**
     * The {@code ~/.gemini} root, used to sandbox the inline file-preview endpoint. Sessions are read
     * from the store now, so this is the only reason the app still touches the Antigravity layout.
     */
    static Path geminiRoot() {
        return Paths.get(System.getProperty("user.home"), ".gemini");
    }
}
