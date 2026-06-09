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

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.micronaut.langchain4j.annotation.AiService;

@AiService
public interface AnalyzerService {
    @SystemMessage("""
			You are an expert at analyzing JSONL transcripts of Antigravity CLI sessions.
			Your job is to extract the core insights, actions, issues, and actionable recommendations
			(e.g. missing CLI tools, helpful skills to create, or AGENTS.md advice) into a structured format.
			""")
    @UserMessage("""
			Please analyze the following JSONL transcript of an Antigravity CLI session.

			CRITICAL INSTRUCTIONS:
			- NEVER fall into an infinite repetition loop. Do NOT repeat the exact same phrase or word over and over.
			- Be succinct and concise in your lists. Keep the overall size of the output compact.
			- Keep description texts short. Each issue or action description MUST be 1 or 2 sentences maximum.
			- DO NOT output Base64. DO NOT output infinitely repeating words or phrases. If you find yourself repeating, STOP immediately.
			- Your summary MUST BE SHORT. No more than 3 paragraphs. Do NOT embed raw logs, code, or large quotes in the summary.
			- Output MUST be exclusively in English. No other languages are permitted.

			Transcript:
			{{transcript}}
			""")
    AnalysisResponse analyze(@V("transcript") String transcript);

    @UserMessage("""
			Here is the structured analysis from the previous parts of the conversation:

			{{previousAnalysis}}

			Please update and enhance this analysis using the next section of the transcript below.
			CRITICAL INSTRUCTIONS:
			- Incorporate the new context into the existing analysis.
			- NEVER fall into an infinite repetition loop. Do NOT repeat the exact same phrase or word over and over.
			- Merge new elements concisely. If an issue or action is already documented or very similar, do NOT add it again.
			- Be succinct and concise in your lists. Keep the overall size of the output compact.
			- DO NOT output Base64. DO NOT output infinitely repeating words or phrases. If you find yourself repeating, STOP immediately.
			- Your summary MUST BE SHORT. No more than 3 paragraphs. Do NOT embed raw logs, code, or large quotes in the summary.
			- Output MUST be exclusively in English. No other languages are permitted.
			- Update the `summary` to reflect the accumulated narrative from the very beginning of the session up to this chunk.

			New Transcript Chunk:
			{{transcript}}
			""")
    AnalysisResponse refineAnalysis(
        @V("previousAnalysis") String previousAnalysis,
        @V("transcript") String transcript
    );

    @UserMessage("""
			Here is a list of partial structured analysis objects, each corresponding to a distinct segment of the same session:

			{{combinedSummariesJson}}

			Please consolidate them into a single, unified, and comprehensive structured analysis.
			CRITICAL INSTRUCTIONS:
			- Merge items thoughtfully. Avoid duplicating issues or actions.
			- NEVER fall into an infinite repetition loop.
			- Ensure your summary provides an overarching narrative of the entire session.
			- Your summary MUST BE SHORT. No more than 3 paragraphs.
			- DO NOT output Base64 or raw logs.
			- Output MUST be exclusively in English. No other languages are permitted.
			""")
    AnalysisResponse consolidateAnalysis(@V("combinedSummariesJson") String combinedSummariesJson);
}
