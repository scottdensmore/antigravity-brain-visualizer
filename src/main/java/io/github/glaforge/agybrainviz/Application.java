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

import io.micronaut.runtime.Micronaut;
import java.util.Locale;
import java.util.Map;

public class Application {

    public static void main(String[] args) {
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                printHelp();
                System.exit(0);
            } else if ("-v".equals(arg) || "--version".equals(arg)) {
                System.out.println("Agent Brain Visualizer version " + Version.VERSION);
                System.exit(0);
            }
        }
        applyDotEnvFrameworkSettings();
        Micronaut.run(Application.class, args);
    }

    /**
     * Feeds {@code MICRONAUT_*} entries from a {@code .env} file into system properties (e.g.
     * {@code MICRONAUT_SERVER_PORT} → {@code micronaut.server.port}) so the framework honours them.
     * Real environment variables and explicit {@code -D} flags take precedence over the file. The AI
     * settings are read separately by {@link AiConfig}.
     */
    private static void applyDotEnvFrameworkSettings() {
        applyFrameworkSettings(DotEnv.values());
    }

    /** The pure half of {@link #applyDotEnvFrameworkSettings()}, so the precedence rules are testable. */
    static void applyFrameworkSettings(Map<String, String> values) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!key.startsWith("MICRONAUT_")) continue;
            // A blank value would fail Micronaut's property resolution; ignore the line instead.
            if (value == null || value.isBlank()) continue;
            if (System.getenv(key) != null) continue; // a real environment variable wins
            String property = key.toLowerCase(Locale.ROOT).replace('_', '.');
            if (System.getProperty(property) == null) { // an explicit -D flag wins
                System.setProperty(property, value);
            }
        }
    }

    private static void printHelp() {
        System.out.println("""
                Agent Brain Visualizer
                ======================
                A web interface for inspecting AI agent execution transcripts.

                Usage:
                  cp .env.example .env    # then edit .env (or export the variables)
                  ./agy-brain-viz [options]

                Configuration is read from real environment variables first, then from a `.env`
                file in the current directory (copy `.env.example` to get started).

                Options:
                  -Dmicronaut.server.port=<port>   Run on a custom port (default: 8080)
                  -Ddotenv.enabled=false           Ignore any .env file
                  -Ddotenv.path=<path>             Load a .env file from a custom path
                  -h, --help                       Show this help message and exit
                  -v, --version                    Print the version information and exit

                Environment Variables (or .env entries):
                  AI_PROVIDER                      `gemini` (default) or `ollama`
                  GEMINI_API_KEY                   Required to generate summaries with Gemini
                  GEMINI_MODEL                     Gemini model name
                  OLLAMA_BASE_URL, OLLAMA_MODEL    Local Ollama server and model
                  MICRONAUT_SERVER_PORT            Overrides the default server port
                """);
    }
}
