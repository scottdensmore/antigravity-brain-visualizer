// Copyright 2026 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Command agent-ingest scans a machine's local agent transcripts (Antigravity,
// OpenAI Codex, Claude Code) and pushes them to a running Agent Brain Visualizer,
// so trajectories captured anywhere are visible from any machine pointed at the
// same store. It is idempotent: a session already stored with identical content
// is skipped, so it is safe to run repeatedly or on a schedule.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/url"
	"os"
	"strings"

	"github.com/scottdensmore/antigravity-brain-visualizer/cli/internal/client"
	"github.com/scottdensmore/antigravity-brain-visualizer/cli/internal/scan"
)

// version is overridden at build time with -ldflags "-X main.version=...".
var version = "dev"

const (
	exitOK     = 0 // everything requested was ingested or already up to date
	exitFailed = 1 // the sync ran but something failed (server error, rejected session)
	exitUsage  = 2 // the command was invoked incorrectly
)

const defaultServer = "http://localhost:8080"
const defaultBatchSize = 100

type config struct {
	server    string
	token     string
	sources   []string
	home      string
	dryRun    bool
	jsonOut   bool
	quiet     bool
	batchSize int
}

func main() {
	os.Exit(cli(os.Args[1:], os.Stdout, os.Stderr, os.Getenv))
}

// cli parses arguments and runs the sync, returning the process exit code. It
// takes its streams and environment as parameters so it is fully testable.
func cli(args []string, stdout, stderr io.Writer, getenv func(string) string) int {
	cfg, code, done := parseFlags(args, stdout, stderr, getenv)
	if done {
		return code
	}
	return run(context.Background(), cfg, stdout, stderr)
}

func parseFlags(args []string, stdout, stderr io.Writer, getenv func(string) string) (config, int, bool) {
	fs := flag.NewFlagSet("agent-ingest", flag.ContinueOnError)
	fs.SetOutput(io.Discard) // we print usage and errors ourselves, to the right stream
	fs.Usage = func() {}

	var (
		sources     multiFlag
		server      = fs.String("server", envOr(getenv, "AGENT_INGEST_SERVER", defaultServer), "base URL of the visualizer")
		home        = fs.String("home", "", "home directory to scan (default: the current user's)")
		dryRun      = fs.Bool("dry-run", false, "report what would be pushed without pushing")
		jsonOut     = fs.Bool("json", false, "write a machine-readable summary to stdout")
		quiet       = fs.Bool("quiet", false, "suppress progress on stderr")
		batchSize   = fs.Int("batch-size", defaultBatchSize, "sessions per push request")
		showVersion = fs.Bool("version", false, "print the version and exit")
	)
	fs.Var(&sources, "source", "source to sync; repeatable (default: all)")

	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			printUsage(stdout)
			return config{}, exitOK, true
		}
		fmt.Fprintf(stderr, "agent-ingest: %s\nRun 'agent-ingest --help' for usage.\n", err)
		return config{}, exitUsage, true
	}
	if *showVersion {
		fmt.Fprintf(stdout, "agent-ingest %s\n", version)
		return config{}, exitOK, true
	}
	if fs.NArg() > 0 {
		fmt.Fprintf(stderr, "agent-ingest: unexpected argument %q\nRun 'agent-ingest --help' for usage.\n", fs.Arg(0))
		return config{}, exitUsage, true
	}

	selected := []string(sources)
	if len(selected) == 0 {
		selected = scan.AllSources
	}
	if err := scan.Validate(selected); err != nil {
		fmt.Fprintf(stderr, "agent-ingest: %s\n", err)
		return config{}, exitUsage, true
	}
	if err := validateServer(*server); err != nil {
		fmt.Fprintf(stderr, "agent-ingest: %s\n", err)
		return config{}, exitUsage, true
	}
	if *batchSize < 1 {
		fmt.Fprintf(stderr, "agent-ingest: --batch-size must be at least 1\n")
		return config{}, exitUsage, true
	}

	return config{
		server:    *server,
		token:     getenv("AGENT_INGEST_TOKEN"),
		sources:   selected,
		home:      *home,
		dryRun:    *dryRun,
		jsonOut:   *jsonOut,
		quiet:     *quiet,
		batchSize: *batchSize,
	}, exitOK, false
}

func run(ctx context.Context, cfg config, stdout, stderr io.Writer) int {
	home := cfg.home
	if home == "" {
		h, err := os.UserHomeDir()
		if err != nil {
			fmt.Fprintf(stderr, "agent-ingest: cannot determine home directory: %s\n", err)
			return exitFailed
		}
		home = h
	}

	sessions, err := scan.Scan(home, cfg.sources)
	if err != nil {
		fmt.Fprintf(stderr, "agent-ingest: %s\n", err)
		return exitFailed
	}
	bySource := groupBySource(sessions)

	cl := client.New(cfg.server, cfg.token)
	report := newReport(cfg.dryRun)

	for _, source := range cfg.sources {
		list := bySource[source]
		if len(list) == 0 {
			continue // this machine has no sessions for this source
		}
		progress(cfg, stderr, "%s: %d local session(s)", source, len(list))

		manifest, err := cl.Manifest(ctx, source)
		if err != nil {
			// Record the failure against the source so the summary and --json
			// reflect it — a dropped source used to masquerade as "no sessions".
			fmt.Fprintf(stderr, "agent-ingest: %s\n", err)
			report.add(source, sourceReport{Error: err.Error()})
			continue
		}

		changed := changedSessions(list, manifest)
		sr := sourceReport{Skipped: len(list) - len(changed)}

		if cfg.dryRun {
			sr.Ingested = len(changed) // "would ingest"
			report.add(source, sr)
			continue
		}

		for _, batch := range chunk(changed, cfg.batchSize, maxBatchBytes) {
			r, err := cl.Push(ctx, batch)
			if err != nil {
				fmt.Fprintf(stderr, "agent-ingest: %s\n", err)
				sr.Error = err.Error()
				break
			}
			sr.Ingested += r.Ingested
			sr.Skipped += r.Skipped
			sr.Failed += r.Failed
		}
		report.add(source, sr)
	}

	report.write(stdout, cfg.jsonOut)
	if report.failed {
		return exitFailed
	}
	return exitOK
}

// changedSessions keeps only the sessions whose content differs from what the
// server already stores, so an unchanged corpus uploads nothing.
func changedSessions(list []scan.Session, manifest map[string]string) []client.PushSession {
	var changed []client.PushSession
	for _, s := range list {
		if manifest[s.ID] == s.Hash {
			continue
		}
		changed = append(changed, client.PushSession{
			Source:      s.Source,
			ID:          s.ID,
			SourceMtime: s.Mtime,
			Raw:         s.Raw,
		})
	}
	return changed
}

func groupBySource(sessions []scan.Session) map[string][]scan.Session {
	out := map[string][]scan.Session{}
	for _, s := range sessions {
		out[s.Source] = append(out[s.Source], s)
	}
	return out
}

// Transcripts can be large, so a request is bounded by cumulative raw bytes, not just a session
// count that could easily blow past the server's request-size limit. The budget leaves generous
// headroom under that limit (JSON escaping can inflate raw bytes on the wire).
const maxBatchBytes = 16 << 20 // 16 MiB

// chunk groups sessions into requests, flushing when the next session would exceed either the count
// cap or the byte budget. A session larger than the budget is sent alone rather than dropped or
// split; one that large is on its own the only request that can exceed the budget.
func chunk(sessions []client.PushSession, maxCount, maxBytes int) [][]client.PushSession {
	var batches [][]client.PushSession
	var current []client.PushSession
	currentBytes := 0
	for _, s := range sessions {
		size := len(s.Raw)
		if len(current) > 0 && (len(current) >= maxCount || currentBytes+size > maxBytes) {
			batches = append(batches, current)
			current = nil
			currentBytes = 0
		}
		current = append(current, s)
		currentBytes += size
	}
	if len(current) > 0 {
		batches = append(batches, current)
	}
	return batches
}

func progress(cfg config, stderr io.Writer, format string, args ...any) {
	if cfg.quiet || !isTerminal(stderr) {
		return
	}
	fmt.Fprintf(stderr, format+"\n", args...)
}

// isTerminal reports whether w is an interactive terminal, so progress noise
// stays out of pipes and files.
func isTerminal(w io.Writer) bool {
	f, ok := w.(*os.File)
	if !ok {
		return false
	}
	info, err := f.Stat()
	return err == nil && info.Mode()&os.ModeCharDevice != 0
}

// validateServer rejects a --server that isn't an http(s) URL at parse time, so
// a bad value is a usage error rather than a confusing failure deep in a request.
func validateServer(server string) error {
	u, err := url.Parse(server)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return fmt.Errorf("--server must be an http(s) URL, got %q", server)
	}
	return nil
}

func envOr(getenv func(string) string, key, fallback string) string {
	if v := getenv(key); v != "" {
		return v
	}
	return fallback
}

func printUsage(w io.Writer) {
	fmt.Fprintf(w, `agent-ingest — push local agent transcripts to an Agent Brain Visualizer

Usage:
  agent-ingest [flags]

It scans this machine for Antigravity, OpenAI Codex, and Claude Code sessions and
uploads them to the visualizer's ingest API. Sessions already stored with the same
content are skipped, so running it repeatedly (e.g. on a schedule) is safe.

Flags:
  --server URL      base URL of the visualizer (default %q; or $AGENT_INGEST_SERVER)
  --source NAME     source to sync; repeatable (default: all). One of:
                    %s
  --home DIR        home directory to scan (default: the current user's)
  --batch-size N    max sessions per push request (default %d; a request is also
                    capped at ~16 MiB of transcript, and one larger session is
                    sent on its own)
  --dry-run         report what would be pushed, without pushing (still queries
                    the server's manifest to compute the diff)
  --json            write a machine-readable summary to stdout
  --quiet           suppress progress on stderr
  --version         print the version and exit
  -h, --help        show this help and exit

Environment:
  AGENT_INGEST_SERVER   default for --server
  AGENT_INGEST_TOKEN    bearer token, sent when the server sets INGEST_TOKEN.
                        Passed via the environment (not a flag) so it never lands
                        in shell history or the process list.

Exit codes:
  0  success (everything was ingested or already up to date)
  1  the sync ran but something failed
  2  the command was invoked incorrectly
`, defaultServer, strings.Join(scan.AllSources, ", "), defaultBatchSize)
}

// multiFlag collects a repeatable string flag.
type multiFlag []string

func (m *multiFlag) String() string { return strings.Join(*m, ",") }

func (m *multiFlag) Set(v string) error {
	// Accept both a repeated flag and a comma-separated list.
	for _, part := range strings.Split(v, ",") {
		if p := strings.TrimSpace(part); p != "" {
			*m = append(*m, p)
		}
	}
	return nil
}

// sourceReport is the outcome for one source. Error is set when the source could
// not be synced, so a failure is never silently dropped from the summary.
type sourceReport struct {
	Ingested int    `json:"ingested"`
	Skipped  int    `json:"skipped"`
	Failed   int    `json:"failed"`
	Error    string `json:"error,omitempty"`
}

func (s sourceReport) errored() bool { return s.Error != "" || s.Failed > 0 }

// report accumulates per-source outcomes and renders the summary.
type report struct {
	dryRun bool
	order  []string
	perSrc map[string]sourceReport
	total  client.Result
	failed bool
}

func newReport(dryRun bool) *report {
	return &report{dryRun: dryRun, perSrc: map[string]sourceReport{}}
}

func (r *report) add(source string, sr sourceReport) {
	if _, seen := r.perSrc[source]; !seen {
		r.order = append(r.order, source)
	}
	r.perSrc[source] = sr
	r.total.Ingested += sr.Ingested
	r.total.Skipped += sr.Skipped
	r.total.Failed += sr.Failed
	if sr.errored() {
		r.failed = true
	}
}

func (r *report) write(stdout io.Writer, jsonOut bool) {
	if jsonOut {
		payload := map[string]any{
			"ok":      !r.failed,
			"dryRun":  r.dryRun,
			"total":   r.total,
			"sources": r.perSrc,
		}
		enc := json.NewEncoder(stdout)
		enc.SetIndent("", "  ")
		_ = enc.Encode(payload)
		return
	}

	if len(r.order) == 0 {
		fmt.Fprintln(stdout, "No sessions found to ingest.")
		return
	}
	verb := "ingested"
	if r.dryRun {
		verb = "would ingest"
	}
	width := len("total")
	for _, s := range r.order {
		width = max(width, len(s))
	}
	for _, s := range r.order {
		sr := r.perSrc[s]
		if sr.Error != "" {
			fmt.Fprintf(stdout, "%-*s  error: %s\n", width, s, sr.Error)
			continue
		}
		fmt.Fprintf(stdout, "%-*s  %d %s, %d skipped, %d failed\n", width, s, sr.Ingested, verb, sr.Skipped, sr.Failed)
	}
	fmt.Fprintf(stdout, "%-*s  %d %s, %d skipped, %d failed\n", width, "total", r.total.Ingested, verb, r.total.Skipped, r.total.Failed)
}
