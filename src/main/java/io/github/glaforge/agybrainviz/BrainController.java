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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Controller("/api/brain")
public class BrainController {

    private final List<SessionSource> sessionSources;

    @Inject
    public BrainController(List<SessionSource> sessionSources) {
        this.sessionSources = sessionSources;
    }

    private Optional<SessionSource> sourceFor(String flavor) {
        return sessionSources.stream().filter(s -> s.handles(flavor)).findFirst();
    }

    @ExecuteOn(TaskExecutors.IO)
    @Get("/conversations")
    public List<Map<String, String>> listConversations(@QueryValue Optional<String> flavor) {
        Optional<SessionSource> source = sourceFor(flavor.orElse(""));
        if (source.isPresent()) {
            return source.get().listConversations();
        }
        Path brainPath = AntigravityPaths.brainDir(flavor.orElse(AntigravityPaths.DEFAULT_FLAVOR));
        if (!Files.exists(brainPath)) return List.of();

        try (Stream<Path> paths = Files.list(brainPath)) {
            return paths
                .filter(p -> {
                    if (!Files.isDirectory(p)) return false;
                    Path transcriptPath = AntigravityPaths.transcript(AntigravityPaths.logsDir(p));
                    if (!Files.exists(transcriptPath)) return false;
                    try {
                        return Files.size(transcriptPath) > 0;
                    } catch (IOException e) {
                        return false;
                    }
                })
                .map(p -> {
                    String id = p.getFileName().toString();
                    Map<String, String> info = new HashMap<>();
                    info.put("id", id);

                    Path logs = AntigravityPaths.logsDir(p);
                    String summary = "Conversation " + id.substring(0, 8);
                    Path shortTitlePath = AntigravityPaths.shortTitle(logs);
                    if (Files.exists(shortTitlePath)) {
                        try {
                            summary = Files.readString(shortTitlePath).trim();
                        } catch (IOException e) {}
                    } else {
                        Path transcriptPath = AntigravityPaths.transcript(logs);
                        if (Files.exists(transcriptPath)) {
                            try (BufferedReader reader = Files.newBufferedReader(transcriptPath)) {
                                ObjectMapper mapper = new ObjectMapper();
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (line.contains("\"USER_INPUT\"")) {
                                        Map<String, Object> map = mapper.readValue(line, Map.class);
                                        if ("USER_INPUT".equals(map.get("type"))) {
                                            String content = (String) map.getOrDefault(
                                                "content",
                                                ""
                                            );
                                            content =
                                                content.replaceAll("(?s)<USER_REQUEST>\\s*", "");
                                            int endIdx = content.indexOf("</USER_REQUEST>");
                                            if (endIdx != -1) {
                                                content = content.substring(0, endIdx);
                                            }
                                            content = content.trim();
                                            if (content.length() > 80) {
                                                content = content.substring(0, 80) + "...";
                                            }
                                            if (!content.isEmpty()) {
                                                summary = content;
                                            }
                                            break;
                                        }
                                    }
                                }
                            } catch (Exception e) {}
                        }
                    }

                    info.put("summary", summary);
                    try {
                        Path transcriptPath = AntigravityPaths.transcript(logs);
                        long modified = Files.getLastModifiedTime(transcriptPath).toMillis();
                        info.put("updatedAt", String.valueOf(modified));
                    } catch (IOException e) {
                        info.put("updatedAt", "0");
                    }
                    return info;
                })
                .sorted((a, b) ->
                    Long.compare(
                        Long.parseLong(b.get("updatedAt")),
                        Long.parseLong(a.get("updatedAt"))
                    )
                )
                .collect(Collectors.toList());
        } catch (IOException e) {
            e.printStackTrace();
            return List.of();
        }
    }

    @ExecuteOn(TaskExecutors.IO)
    @Get(value = "/conversations/{id}/transcript", produces = "application/json")
    public String getTranscript(@PathVariable String id, @QueryValue Optional<String> flavor)
        throws IOException {
        Optional<SessionSource> source = sourceFor(flavor.orElse(""));
        if (source.isPresent()) {
            return source.get().transcriptJson(id);
        }
        Path logs = AntigravityPaths.logsDir(flavor.orElse(AntigravityPaths.DEFAULT_FLAVOR), id);
        Path transcriptPath = AntigravityPaths.transcriptFull(logs);
        if (!Files.exists(transcriptPath)) {
            transcriptPath = AntigravityPaths.transcript(logs);
        }
        if (!Files.exists(transcriptPath)) {
            return "[]";
        }

        // Read JSONL and convert to a JSON array of objects
        try (Stream<String> lines = Files.lines(transcriptPath)) {
            String jsonArray = lines
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining(",", "[", "]"));
            return jsonArray;
        }
    }

    @ExecuteOn(TaskExecutors.IO)
    @Get(value = "/file", produces = "text/plain")
    public HttpResponse<String> getFileContent(@QueryValue String path) {
        try {
            Path filePath = Paths.get(path).normalize();
            Path geminiDir = AntigravityPaths.geminiRoot().normalize();
            if (!filePath.startsWith(geminiDir)) {
                return HttpResponse.unauthorized();
            }
            if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
                return HttpResponse.ok(Files.readString(filePath));
            } else {
                return HttpResponse.notFound("File not found or is a directory.");
            }
        } catch (IOException e) {
            return HttpResponse.serverError("Error reading file: " + e.getMessage());
        }
    }
}
