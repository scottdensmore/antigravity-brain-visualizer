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
const COLUMNS = [
  "savedAt",
  "flavor",
  "modelLabel",
  "sessionCount",
  "evaluatedSessions",
  "avgScore",
  "judged",
  "judgedSessions",
  "avgFaithfulness",
  "avgActionability",
  "avgClarity",
];

// Quote a cell only when it contains a comma, quote, or newline; double embedded quotes (RFC 4180).
function csvCell(value) {
  const s = value === null || value === undefined ? "" : String(value);
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

/** A CSV document (header row + one row per saved run) for a run-history list. */
export function historyCsv(history) {
  const runs = Array.isArray(history) ? history : [];
  const rows = runs.map((run) =>
    COLUMNS.map((col) => csvCell(run[col])).join(",")
  );
  return [COLUMNS.join(","), ...rows].join("\n") + "\n";
}
