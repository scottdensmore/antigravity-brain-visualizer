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

package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/scottdensmore/antigravity-brain-visualizer/cli/internal/scan"
)

// sha reuses the scanner's hash so the test asserts against the real contract.
func sha(s string) string { return scan.Sha256Hex(s) }

// fakeServer stands in for the app's ingest API: it holds a manifest and records
// every pushed batch, so a test can drive the whole scan → diff → push loop.
type fakeServer struct {
	mu       sync.Mutex
	manifest map[string]map[string]string // source -> id -> hash
	pushed   [][]map[string]any
	srv      *httptest.Server
}

func newFakeServer() *fakeServer {
	f := &fakeServer{manifest: map[string]map[string]string{}}
	mux := http.NewServeMux()
	mux.HandleFunc("/api/ingest/manifest", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		m := f.manifest[r.URL.Query().Get("source")]
		if m == nil {
			m = map[string]string{}
		}
		_ = json.NewEncoder(w).Encode(m)
	})
	mux.HandleFunc("/api/ingest/sessions", func(w http.ResponseWriter, r *http.Request) {
		var batch []map[string]any
		_ = json.NewDecoder(r.Body).Decode(&batch)
		f.mu.Lock()
		f.pushed = append(f.pushed, batch)
		f.mu.Unlock()
		_ = json.NewEncoder(w).Encode(map[string]int{"ingested": len(batch), "skipped": 0, "failed": 0})
	})
	f.srv = httptest.NewServer(mux)
	return f
}

func (f *fakeServer) pushedCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := 0
	for _, b := range f.pushed {
		n += len(b)
	}
	return n
}

func (f *fakeServer) batchCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.pushed)
}

func write(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func seedHome(t *testing.T) string {
	home := t.TempDir()
	write(t, filepath.Join(home, ".codex/sessions/2026/06/20/rollout-a.jsonl"), "codex-a")
	write(t, filepath.Join(home, ".claude/projects/p/cc-b.jsonl"), "claude-b")
	return home
}

func runCLI(t *testing.T, args []string, getenv func(string) string) (int, string, string) {
	t.Helper()
	var out, errBuf bytes.Buffer
	if getenv == nil {
		getenv = func(string) string { return "" }
	}
	code := cli(args, &out, &errBuf, getenv)
	return code, out.String(), errBuf.String()
}

func TestPushesEveryDiscoveredSessionWhenManifestIsEmpty(t *testing.T) {
	f := newFakeServer()
	defer f.srv.Close()
	home := seedHome(t)

	code, stdout, _ := runCLI(t, []string{"--server", f.srv.URL, "--home", home}, nil)
	if code != 0 {
		t.Fatalf("exit code = %d, want 0; stdout=%q", code, stdout)
	}
	if f.pushedCount() != 2 {
		t.Fatalf("pushed %d sessions, want 2", f.pushedCount())
	}
	if !strings.Contains(stdout, "2 ingested") {
		t.Errorf("summary should report 2 ingested; got %q", stdout)
	}
}

func TestSkipsSessionsAlreadyInTheManifest(t *testing.T) {
	f := newFakeServer()
	defer f.srv.Close()
	home := seedHome(t)
	// The server already has codex/rollout-a with the same content, so only the
	// Claude session should be pushed — the manifest diff must prevent duplicates.
	f.manifest["codex"] = map[string]string{"rollout-a": sha("codex-a")}

	code, stdout, _ := runCLI(t, []string{"--server", f.srv.URL, "--home", home}, nil)
	if code != 0 {
		t.Fatalf("exit = %d", code)
	}
	if f.pushedCount() != 1 {
		t.Fatalf("pushed %d, want 1 (the changed session only)", f.pushedCount())
	}
	if !strings.Contains(stdout, "skipped") {
		t.Errorf("summary should mention skipped; got %q", stdout)
	}
}

func TestDryRunPushesNothing(t *testing.T) {
	f := newFakeServer()
	defer f.srv.Close()
	home := seedHome(t)

	code, stdout, _ := runCLI(t, []string{"--server", f.srv.URL, "--home", home, "--dry-run"}, nil)
	if code != 0 {
		t.Fatalf("exit = %d", code)
	}
	if f.pushedCount() != 0 {
		t.Fatalf("dry-run pushed %d sessions, want 0", f.pushedCount())
	}
	if !strings.Contains(strings.ToLower(stdout), "would") {
		t.Errorf("dry-run output should say what it would do; got %q", stdout)
	}
}

func TestJSONOutputIsMachineReadableOnStdout(t *testing.T) {
	f := newFakeServer()
	defer f.srv.Close()
	home := seedHome(t)

	code, stdout, _ := runCLI(t, []string{"--server", f.srv.URL, "--home", home, "--json"}, nil)
	if code != 0 {
		t.Fatalf("exit = %d", code)
	}
	var parsed struct {
		Total struct{ Ingested, Skipped, Failed int } `json:"total"`
	}
	if err := json.Unmarshal([]byte(stdout), &parsed); err != nil {
		t.Fatalf("--json stdout is not valid JSON: %v\n%s", err, stdout)
	}
	if parsed.Total.Ingested != 2 {
		t.Errorf("total.ingested = %d, want 2", parsed.Total.Ingested)
	}
}

func TestSelectsASingleSource(t *testing.T) {
	f := newFakeServer()
	defer f.srv.Close()
	home := seedHome(t)

	code, _, _ := runCLI(t, []string{"--server", f.srv.URL, "--home", home, "--source", "codex"}, nil)
	if code != 0 {
		t.Fatalf("exit = %d", code)
	}
	if f.pushedCount() != 1 {
		t.Fatalf("pushed %d, want only the 1 codex session", f.pushedCount())
	}
}

func TestServerErrorExitsNonZero(t *testing.T) {
	down := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer down.Close()
	home := seedHome(t)

	code, _, stderr := runCLI(t, []string{"--server", down.URL, "--home", home}, nil)
	if code == 0 {
		t.Fatal("a failed sync must exit non-zero")
	}
	if strings.TrimSpace(stderr) == "" {
		t.Error("a failure should explain itself on stderr")
	}
}

func TestUnknownSourceIsAUsageError(t *testing.T) {
	code, _, stderr := runCLI(t, []string{"--source", "borg"}, nil)
	if code != 2 {
		t.Fatalf("exit = %d, want 2 for a usage error", code)
	}
	if !strings.Contains(stderr, "borg") {
		t.Errorf("stderr should name the bad source; got %q", stderr)
	}
}

func TestHelpGoesToStdoutAndExitsZero(t *testing.T) {
	code, stdout, _ := runCLI(t, []string{"--help"}, nil)
	if code != 0 {
		t.Fatalf("--help exit = %d, want 0", code)
	}
	for _, want := range []string{"agent-ingest", "--server", "--dry-run", "AGENT_INGEST_TOKEN"} {
		if !strings.Contains(stdout, want) {
			t.Errorf("help missing %q", want)
		}
	}
}

func TestAFailedSourceIsReportedNotHiddenAsNoSessions(t *testing.T) {
	// Regression: a source whose manifest fetch fails used to be dropped from the
	// report, so stdout printed "No sessions found" (and --json hid the failure)
	// even though sessions existed and the run exited non-zero.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()
	home := seedHome(t) // has a codex and a claude-code session

	code, stdout, stderr := runCLI(t, []string{"--server", srv.URL, "--home", home}, nil)
	if code != 1 {
		t.Fatalf("exit = %d, want 1", code)
	}
	if strings.Contains(stdout, "No sessions found") {
		t.Errorf("must not claim 'no sessions' when sessions exist and the sync failed; stdout=%q", stdout)
	}
	if !strings.Contains(stdout, "codex") || !strings.Contains(stdout, "claude-code") {
		t.Errorf("errored sources must appear in the summary; stdout=%q", stdout)
	}
	if strings.TrimSpace(stderr) == "" {
		t.Errorf("the failure should also explain itself on stderr")
	}
}

func TestJSONMarksFailureWithOkFalse(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()
	home := seedHome(t)

	code, stdout, _ := runCLI(t, []string{"--server", srv.URL, "--home", home, "--json"}, nil)
	if code != 1 {
		t.Fatalf("exit = %d, want 1", code)
	}
	var parsed struct {
		OK      bool `json:"ok"`
		Sources map[string]struct {
			Error string `json:"error"`
		} `json:"sources"`
	}
	if err := json.Unmarshal([]byte(stdout), &parsed); err != nil {
		t.Fatalf("--json is not valid JSON: %v\n%s", err, stdout)
	}
	if parsed.OK {
		t.Errorf("ok must be false when the sync failed")
	}
	if parsed.Sources["codex"].Error == "" {
		t.Errorf("an errored source must carry an error message in --json; got %s", stdout)
	}
}

func TestInvalidServerIsAUsageError(t *testing.T) {
	// A bad invocation value is a usage error (exit 2), not a deep runtime failure.
	code, _, stderr := runCLI(t, []string{"--server", "", "--home", t.TempDir()}, nil)
	if code != 2 {
		t.Fatalf("exit = %d, want 2 for an empty --server", code)
	}
	if strings.TrimSpace(stderr) == "" {
		t.Error("an invalid --server should explain itself on stderr")
	}
	code, _, _ = runCLI(t, []string{"--server", "not-a-url", "--home", t.TempDir()}, nil)
	if code != 2 {
		t.Fatalf("exit = %d, want 2 for a schemeless --server", code)
	}
}

func TestCommaSeparatedSourcesAreAccepted(t *testing.T) {
	f := newFakeServer()
	defer f.srv.Close()
	home := seedHome(t)

	code, _, _ := runCLI(t, []string{"--server", f.srv.URL, "--home", home, "--source", "codex,claude-code"}, nil)
	if code != 0 {
		t.Fatalf("exit = %d", code)
	}
	if f.pushedCount() != 2 {
		t.Fatalf("pushed %d, want both comma-listed sources (2)", f.pushedCount())
	}
}

func TestNonUTF8TranscriptIsIngestedOnceThenSkipped(t *testing.T) {
	// End-to-end guard for the hash contract: a server that hashes the raw it
	// receives exactly as the Java server does (sha256 of the received bytes). A
	// transcript with an invalid UTF-8 byte must be pushed once and then recognized
	// as unchanged — otherwise it re-uploads on every run.
	stored := map[string]string{}
	var mu sync.Mutex
	var pushes int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/ingest/manifest" {
			mu.Lock()
			_ = json.NewEncoder(w).Encode(stored)
			mu.Unlock()
			return
		}
		var batch []struct{ ID, Raw string }
		_ = json.NewDecoder(r.Body).Decode(&batch)
		mu.Lock()
		for _, s := range batch {
			pushes++
			sum := sha256.Sum256([]byte(s.Raw)) // what Ingestor.sha256 does on the server
			stored[s.ID] = hex.EncodeToString(sum[:])
		}
		mu.Unlock()
		_, _ = w.Write([]byte(`{"ingested":1,"skipped":0,"failed":0}`))
	}))
	defer srv.Close()

	home := t.TempDir()
	write(t, filepath.Join(home, ".codex/sessions/2026/06/20/r.jsonl"), "line\xffend")

	args := []string{"--server", srv.URL, "--home", home, "--source", "codex"}
	if code, _, _ := runCLI(t, args, nil); code != 0 {
		t.Fatalf("first run exit = %d", code)
	}
	if code, _, _ := runCLI(t, args, nil); code != 0 {
		t.Fatalf("second run exit = %d", code)
	}
	mu.Lock()
	defer mu.Unlock()
	if pushes != 1 {
		t.Fatalf("pushed %d times; a non-UTF-8 transcript must upload once then skip", pushes)
	}
}

func TestBatchSizeSplitsIntoSeparateRequests(t *testing.T) {
	f := newFakeServer()
	defer f.srv.Close()
	home := t.TempDir()
	write(t, filepath.Join(home, ".codex/sessions/a.jsonl"), "a")
	write(t, filepath.Join(home, ".codex/sessions/b.jsonl"), "b")
	write(t, filepath.Join(home, ".codex/sessions/c.jsonl"), "c")

	code, _, _ := runCLI(t, []string{"--server", f.srv.URL, "--home", home, "--source", "codex", "--batch-size", "2"}, nil)
	if code != 0 {
		t.Fatalf("exit = %d", code)
	}
	if f.pushedCount() != 3 {
		t.Fatalf("pushed %d sessions, want 3", f.pushedCount())
	}
	if f.batchCount() != 2 {
		t.Fatalf("sent %d requests, want 2 (3 sessions at batch-size 2)", f.batchCount())
	}
}

func TestTokenComesFromTheEnvironment(t *testing.T) {
	var gotAuth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		if r.URL.Path == "/api/ingest/manifest" {
			_, _ = w.Write([]byte("{}"))
			return
		}
		_, _ = w.Write([]byte(`{"ingested":0,"skipped":0,"failed":0}`))
	}))
	defer srv.Close()
	home := seedHome(t)
	env := func(k string) string {
		if k == "AGENT_INGEST_TOKEN" {
			return "s3cret"
		}
		return ""
	}

	code, _, _ := runCLI(t, []string{"--server", srv.URL, "--home", home, "--source", "codex"}, env)
	if code != 0 {
		t.Fatalf("exit = %d", code)
	}
	if gotAuth != "Bearer s3cret" {
		t.Errorf("Authorization = %q, want the env token", gotAuth)
	}
}
