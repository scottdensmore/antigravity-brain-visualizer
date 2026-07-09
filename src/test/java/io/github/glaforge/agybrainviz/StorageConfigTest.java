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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class StorageConfigTest {

    private StorageConfig config(String url, String user, String password, String token) {
        return new StorageConfig(url, user, password, token);
    }

    @Test
    void fallsBackToTheDockerComposeDefaults() {
        StorageConfig unset = config(null, null, null, null);
        assertEquals(StorageConfig.DEFAULT_DATABASE_URL, unset.databaseUrl());
        assertEquals(StorageConfig.DEFAULT_USER, unset.user());
        assertEquals(StorageConfig.DEFAULT_PASSWORD, unset.password());

        // A blank value is a missing value, not a deliberate override: an empty `.env` entry must
        // not produce an unusable connection.
        StorageConfig blank = config("  ", "", " ", null);
        assertEquals(StorageConfig.DEFAULT_DATABASE_URL, blank.databaseUrl());
        assertEquals(StorageConfig.DEFAULT_USER, blank.user());
        assertEquals(StorageConfig.DEFAULT_PASSWORD, blank.password());
    }

    @Test
    void explicitValuesWinOverTheDefaults() {
        StorageConfig hosted = config(
            "jdbc:postgresql://db.example.com:5432/prod?sslmode=require",
            "scott",
            "s3cret",
            null
        );
        assertEquals(
            "jdbc:postgresql://db.example.com:5432/prod?sslmode=require",
            hosted.databaseUrl()
        );
        assertEquals("scott", hosted.user());
        assertEquals("s3cret", hosted.password());
    }

    @Test
    void theIngestTokenIsAbsentUntilItIsSet() {
        assertEquals(Optional.empty(), config(null, null, null, null).ingestToken());
        assertEquals(Optional.empty(), config(null, null, null, "   ").ingestToken());
        assertEquals(Optional.of("t0ken"), config(null, null, null, "t0ken").ingestToken());
    }
}
