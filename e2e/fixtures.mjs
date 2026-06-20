// Seeds a fake ~/.gemini brain directory tree used by the end-to-end tests. The Micronaut server is
// launched with -Duser.home pointed at this directory, so the controllers read these files as if
// they were a real user's Antigravity sessions.
//
// Every session is given a cached summary.json so that selecting it serves the analysis from cache
// (the server runs with a dummy GEMINI_API_KEY) and never calls the real LLM.

import fs from "node:fs";
import path from "node:path";

function writeSession(brainDir, id, { transcript, shortTitle, summary, mtime }) {
  const logs = path.join(brainDir, id, ".system_generated", "logs");
  fs.mkdirSync(logs, { recursive: true });
  const transcriptPath = path.join(logs, "transcript.jsonl");
  fs.writeFileSync(transcriptPath, transcript);
  fs.writeFileSync(path.join(logs, "short_title.txt"), shortTitle);
  fs.writeFileSync(path.join(logs, "summary.json"), JSON.stringify(summary));
  // The session list sorts by transcript mtime, so pin it for deterministic ordering.
  if (mtime) {
    const t = mtime.getTime() / 1000;
    fs.utimesSync(transcriptPath, t, t);
  }
}

function jsonl(lines) {
  return lines.map((l) => JSON.stringify(l)).join("\n") + "\n";
}

export function seedFixtures(home) {
  // Start clean so re-runs are deterministic.
  const gemini = path.join(home, ".gemini");
  fs.rmSync(home, { recursive: true, force: true });
  fs.mkdirSync(gemini, { recursive: true });

  // A real file inside ~/.gemini that the file-preview modal can load.
  const configPath = path.join(gemini, "config.txt");
  fs.writeFileSync(configPath, "parser.mode = strict\nparser.maxDepth = 32\n");

  // ---- flavor: antigravity-cli ----
  const cliBrain = path.join(gemini, "antigravity-cli", "brain");

  writeSession(cliBrain, "sess-0001-parser", {
    shortTitle: "Fix the parser bug",
    transcript: jsonl([
      {
        type: "USER_INPUT",
        source: "USER_EXPLICIT",
        content:
          "<USER_REQUEST>\nPlease fix the parser referenced in @[config.txt]\n</USER_REQUEST>\n" +
          "<ADDITIONAL_METADATA>\n@[config.txt] is a [File]:\n" +
          configPath +
          "\n</ADDITIONAL_METADATA>",
        created_at: "2026-06-19T10:00:00Z",
      },
      {
        source: "MODEL",
        type: "PLANNER_RESPONSE",
        content: "Editing the parser and running the build.",
        tool_calls: [
          { name: "edit_file", args: { TargetFile: "Parser.java" } },
          { name: "run_command", args: { CommandLine: "./gradlew build" } },
        ],
        created_at: "2026-06-19T10:00:05Z",
      },
      {
        type: "ERROR_MESSAGE",
        status: "ERROR",
        error: "NullPointerException",
        content: "Encountered error in step execution: NullPointerException",
        created_at: "2026-06-19T10:00:10Z",
      },
      {
        source: "MODEL",
        type: "PLANNER_RESPONSE",
        content:
          "All fixed! The parser now handles null input. See [the config](file://" +
          configPath +
          ") for details.",
        created_at: "2026-06-19T10:00:15Z",
      },
    ]),
    mtime: new Date("2026-06-19T10:00:00Z"),
    summary: {
      shortTitle: "Fix the parser bug",
      summary: "The agent investigated and fixed a null-pointer bug in the parser.",
      flow: ["Investigated the parser", "Applied a null check"],
      agentActions: [{ action: "edit", description: "Edited the parser source" }],
      issues: [
        { error: "NullPointerException in parser", circumvention: "Added a null check" },
      ],
      recommendations: ["Add a lint rule for null checks"],
    },
  });

  writeSession(cliBrain, "sess-0002-darkmode", {
    shortTitle: "Add dark mode",
    transcript: jsonl([
      {
        type: "USER_INPUT",
        source: "USER_EXPLICIT",
        content: "<USER_REQUEST>\nAdd a dark mode toggle\n</USER_REQUEST>",
        created_at: "2026-06-19T11:00:00Z",
      },
      {
        source: "MODEL",
        type: "PLANNER_RESPONSE",
        content: "Added a dark mode toggle to the settings.",
        created_at: "2026-06-19T11:00:05Z",
      },
    ]),
    mtime: new Date("2026-06-19T11:00:00Z"),
    summary: {
      shortTitle: "Add dark mode",
      summary: "The agent added a dark mode toggle.",
      flow: ["Added a toggle"],
      agentActions: [{ action: "edit", description: "Edited the settings UI" }],
      issues: [],
      recommendations: [],
    },
  });

  // ---- flavor: antigravity-ide (for the flavor-switch journey) ----
  const ideBrain = path.join(gemini, "antigravity-ide", "brain");
  writeSession(ideBrain, "sess-ide-0001", {
    shortTitle: "IDE refactor session",
    transcript: jsonl([
      {
        type: "USER_INPUT",
        source: "USER_EXPLICIT",
        content: "<USER_REQUEST>\nRefactor the IDE plugin\n</USER_REQUEST>",
        created_at: "2026-06-18T09:00:00Z",
      },
    ]),
    summary: {
      shortTitle: "IDE refactor session",
      summary: "The agent refactored the IDE plugin.",
      flow: ["Refactored a module"],
      agentActions: [],
      issues: [],
      recommendations: [],
    },
  });

  // ---- OpenAI Codex sessions (flavor: codex) ----
  const codexFile = path.join(
    gemini,
    "..",
    ".codex",
    "sessions",
    "2026",
    "06",
    "20",
    "rollout-2026-06-20T14-00-00-codexsession.jsonl"
  );
  fs.mkdirSync(path.dirname(codexFile), { recursive: true });
  fs.writeFileSync(
    codexFile,
    jsonl([
      {
        type: "session_meta",
        timestamp: "2026-06-20T14:00:00.000Z",
        payload: { id: "codexsession", cwd: "/repo" },
      },
      {
        type: "event_msg",
        timestamp: "2026-06-20T14:00:00.100Z",
        payload: { type: "user_message", message: "Investigate the flaky test" },
      },
      {
        type: "response_item",
        timestamp: "2026-06-20T14:00:00.100Z",
        payload: {
          type: "message",
          role: "user",
          content: [{ type: "input_text", text: "Investigate the flaky test" }],
        },
      },
      {
        type: "response_item",
        timestamp: "2026-06-20T14:00:02.000Z",
        payload: {
          type: "message",
          role: "assistant",
          content: [{ type: "output_text", text: "Running the test suite to reproduce." }],
        },
      },
      {
        type: "response_item",
        timestamp: "2026-06-20T14:00:03.000Z",
        payload: {
          type: "function_call",
          name: "exec_command",
          arguments: JSON.stringify({ cmd: "npm test" }),
          call_id: "call_codex_1",
        },
      },
      {
        type: "response_item",
        timestamp: "2026-06-20T14:00:05.000Z",
        payload: {
          type: "function_call_output",
          call_id: "call_codex_1",
          output: "Process exited with code 0\nOutput:\nAll tests passed",
        },
      },
    ])
  );

  // Seed a cached Codex analysis so selecting the session serves it from cache (the e2e server runs
  // with a dummy key and would otherwise attempt a real LLM call on auto-analysis).
  const codexCacheDir = path.join(gemini, "..", ".codex", "sessions", ".agybrainviz");
  fs.mkdirSync(codexCacheDir, { recursive: true });
  fs.writeFileSync(
    path.join(codexCacheDir, "rollout-2026-06-20T14-00-00-codexsession.summary.json"),
    JSON.stringify({
      shortTitle: "Flaky test investigation",
      summary: "The agent reproduced and analyzed a flaky test via the suite.",
      flow: ["Ran the test suite"],
      agentActions: [{ action: "run", description: "Ran npm test" }],
      issues: [],
      recommendations: [],
    })
  );

  return { home, configPath };
}
