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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Shared {@code Authorization: Bearer} header matching for the auth filters. */
final class BearerTokens {

    static final String BEARER = "Bearer ";

    private BearerTokens() {}

    /**
     * @return whether {@code header} is a Bearer header carrying exactly {@code expected}. The
     *     comparison is constant-time so it leaks neither the token's length nor its contents.
     */
    static boolean matches(String expected, String header) {
        if (header == null || !header.startsWith(BEARER)) {
            return false;
        }
        byte[] presented = header
            .substring(BEARER.length())
            .trim()
            .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented);
    }
}
