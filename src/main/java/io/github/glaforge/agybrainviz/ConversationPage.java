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
import java.util.Map;

/**
 * One page of a source's conversation list.
 *
 * <p>The store can hold far more sessions than a client wants in one response, so the list is paged.
 * {@code total} lets the UI show "X of N" and decide whether to offer "load more"; {@code limit} and
 * {@code offset} echo back the page that was served (after the server clamped them).
 *
 * @param items this page's {@code {id, summary, updatedAt}} rows, newest first
 * @param total how many sessions the source has in all
 * @param limit the page size actually applied
 * @param offset the offset actually applied
 */
@Serdeable
public record ConversationPage(List<Map<String, String>> items, int total, int limit, int offset) {}
