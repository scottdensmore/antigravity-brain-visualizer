# agent-ingest

A small Go command that scans a machine for local agent transcripts — Antigravity,
OpenAI Codex, and Claude Code — and pushes them to a running
[Agent Brain Visualizer](../README.md), so trajectories captured on any machine are
visible from any other machine pointed at the same store.

The visualizer is the only thing that talks to the database; this CLI is just an
HTTP client for its `/api/ingest` endpoints. It stays deliberately thin — it locates
files, reads them, and uploads them verbatim. **The server parses them**, so the CLI
never has to understand any tool's transcript format.

When a source keeps its own AI analysis on disk (Antigravity's per-session
`summary.json`), the CLI uploads it alongside the transcript so a summary computed on
one machine reaches the shared store without a recompute. The summary is not part of the
change-detection hash — it rides along only when its transcript is pushed. In the common
case that works: a completed session's `summary.json` already exists by the time you run
`agent-ingest`, so it uploads with the transcript on the first push. But a summary that
appears **after** its transcript was already ingested will not sync on its own; it waits
for the transcript to change, which for a finished session may never happen. Re-syncing a
post-hoc summary is a known limitation (the change-detection manifest is transcript-only).

## Build

```bash
cd cli
go build -o agent-ingest .
```

## Use

```bash
# Push everything this machine has to a local visualizer:
agent-ingest

# A remote visualizer, only Claude Code sessions:
agent-ingest --server https://viz.example.com --source claude-code

# See what would happen, without uploading (still queries the manifest):
agent-ingest --dry-run
```

Running it again uploads nothing: each session is keyed by a stable `(source, id)`
and skipped when the server already has identical content, so it is safe to run on a
schedule (`cron`, a `launchd` timer, …).

### Flags

| Flag | Default | Meaning |
| ---- | ------- | ------- |
| `--server URL` | `http://localhost:8080` (or `$AGENT_INGEST_SERVER`) | Base URL of the visualizer. |
| `--source NAME` | all | Source to sync; repeatable. One of `antigravity-cli`, `antigravity-ide`, `codex`, `claude-code`. |
| `--home DIR` | current user's home | Home directory to scan (mainly for testing). |
| `--batch-size N` | `100` | Sessions per push request. |
| `--dry-run` | off | Report what would be pushed, without pushing. |
| `--json` | off | Write a machine-readable summary to stdout. |
| `--quiet` | off | Suppress progress on stderr. |
| `--version` | | Print the version and exit. |
| `-h`, `--help` | | Show help and exit. |

### Environment

| Variable | Meaning |
| -------- | ------- |
| `AGENT_INGEST_SERVER` | Default for `--server`. |
| `AGENT_INGEST_TOKEN` | Bearer token, sent when the server sets `INGEST_TOKEN`. Passed via the environment (not a flag) so it never lands in shell history or the process list. |

### Exit codes

| Code | Meaning |
| ---- | ------- |
| `0` | Success — everything was ingested or already up to date. |
| `1` | The sync ran but something failed (server error, a rejected session). |
| `2` | The command was invoked incorrectly. |

The human summary and `--json` go to **stdout**; progress and errors go to **stderr**,
so the tool composes cleanly in scripts and pipelines. The `--json` object carries an
`ok` boolean (false when any source failed) alongside per-source counts and an `error`
message for any source that could not be synced, so a script can detect failure without
parsing the exit code.

## Test

```bash
cd cli
go test ./...
```

The tests are hermetic: they scan a temporary home and push to an in-process HTTP
stub, so no real agent files or running server are needed.
