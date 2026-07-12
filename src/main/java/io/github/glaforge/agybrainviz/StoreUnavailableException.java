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

/**
 * The trajectory store could not be reached or queried.
 *
 * <p>Repositories raise this instead of letting a {@link java.sql.SQLException} escape, so
 * {@link StoreUnavailableHandler} can answer with a 503 and an actionable message. Answering with an
 * empty list would be worse than an error: the caller could not tell "the database is down" from
 * "you have no sessions yet".
 */
public class StoreUnavailableException extends RuntimeException {

    public StoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
