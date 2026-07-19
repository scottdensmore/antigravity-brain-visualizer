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

package scan

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

// rewriteKeepingStat replaces path's content with a same-length string and
// restores the original mtime, so size and mtime are indistinguishable from
// before — only an actual re-read of the file could observe the new content.
// This is how the tests detect (without flaky permission tricks) whether the
// scanner read the file or trusted the cache.
func rewriteKeepingStat(t *testing.T, path, content string) {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if int64(len(content)) != info.Size() {
		t.Fatalf("rewriteKeepingStat needs same-length content: %d != %d", len(content), info.Size())
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(path, info.ModTime(), info.ModTime()); err != nil {
		t.Fatal(err)
	}
}

func scanOne(t *testing.T, home string, cache *Cache) Session {
	t.Helper()
	sessions, err := ScanWithCache(home, []string{"codex"}, cache)
	if err != nil {
		t.Fatal(err)
	}
	if len(sessions) != 1 {
		t.Fatalf("expected 1 session, got %d", len(sessions))
	}
	return sessions[0]
}

func TestCacheHitReusesHashWithoutRereadingAndLoadRawReadsOnDemand(t *testing.T) {
	home := t.TempDir()
	path := filepath.Join(home, ".codex/sessions/r.jsonl")
	write(t, path, "hello")
	cache := LoadCache(filepath.Join(t.TempDir(), "cache.json"))

	first := scanOne(t, home, cache)
	if first.Hash != Sha256Hex("hello") {
		t.Fatalf("priming scan Hash = %s, want hash of the content", first.Hash)
	}
	if first.Raw != "hello" {
		t.Fatalf("a cache miss must carry the content, got %q", first.Raw)
	}

	// Same size, same mtime, different bytes: a cache hit must return the old
	// hash; only a re-read could see "world".
	rewriteKeepingStat(t, path, "world")

	second := scanOne(t, home, cache)
	if second.Hash != Sha256Hex("hello") {
		t.Fatalf("the file was re-hashed despite an unchanged size and mtime; Hash = %s", second.Hash)
	}
	if second.Raw != "" {
		t.Errorf("a cache hit must skip the read entirely, got Raw = %q", second.Raw)
	}
	if second.Mtime != first.Mtime {
		t.Errorf("Mtime = %d, want the stat mtime %d even on a cache hit", second.Mtime, first.Mtime)
	}

	// The upload path still gets real file content on demand.
	if err := second.LoadRaw(); err != nil {
		t.Fatal(err)
	}
	if second.Raw != "world" {
		t.Errorf("LoadRaw Raw = %q, want the current on-disk content", second.Raw)
	}
}

func TestCachePersistsAcrossSaveAndLoad(t *testing.T) {
	home := t.TempDir()
	path := filepath.Join(home, ".codex/sessions/r.jsonl")
	write(t, path, "hello")
	cachePath := filepath.Join(t.TempDir(), "sub", "dir", "cache.json") // Save must create parents

	c1 := LoadCache(cachePath)
	scanOne(t, home, c1)
	if err := c1.Save(); err != nil {
		t.Fatal(err)
	}

	rewriteKeepingStat(t, path, "world")

	c2 := LoadCache(cachePath)
	s := scanOne(t, home, c2)
	if s.Hash != Sha256Hex("hello") {
		t.Fatalf("a reloaded cache must still hit; Hash = %s", s.Hash)
	}
}

func TestCacheMtimeChangeInvalidatesTheEntry(t *testing.T) {
	home := t.TempDir()
	path := filepath.Join(home, ".codex/sessions/r.jsonl")
	write(t, path, "hello")
	cache := LoadCache(filepath.Join(t.TempDir(), "cache.json"))
	scanOne(t, home, cache)

	// Same size, new mtime: the entry must be invalidated and the file re-read.
	if err := os.WriteFile(path, []byte("world"), 0o644); err != nil {
		t.Fatal(err)
	}
	later := time.Now().Add(2 * time.Hour)
	if err := os.Chtimes(path, later, later); err != nil {
		t.Fatal(err)
	}

	s := scanOne(t, home, cache)
	if s.Hash != Sha256Hex("world") {
		t.Fatalf("an mtime change must force a re-hash; Hash = %s", s.Hash)
	}
	if s.Raw != "world" {
		t.Errorf("a re-hashed session must carry its content, got %q", s.Raw)
	}
}

func TestCacheSizeChangeInvalidatesTheEntry(t *testing.T) {
	home := t.TempDir()
	path := filepath.Join(home, ".codex/sessions/r.jsonl")
	write(t, path, "hello")
	cache := LoadCache(filepath.Join(t.TempDir(), "cache.json"))
	scanOne(t, home, cache)

	// New size, original mtime restored: size alone must invalidate the entry.
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("hello, longer"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(path, info.ModTime(), info.ModTime()); err != nil {
		t.Fatal(err)
	}

	s := scanOne(t, home, cache)
	if s.Hash != Sha256Hex("hello, longer") {
		t.Fatalf("a size change must force a re-hash; Hash = %s", s.Hash)
	}
}

func TestCorruptCacheFileDegradesToFullHashing(t *testing.T) {
	home := t.TempDir()
	write(t, filepath.Join(home, ".codex/sessions/r.jsonl"), "hello")
	cachePath := filepath.Join(t.TempDir(), "cache.json")
	write(t, cachePath, "{not json at all")

	cache := LoadCache(cachePath)
	s := scanOne(t, home, cache)
	if s.Hash != Sha256Hex("hello") {
		t.Fatalf("a corrupt cache must fall back to hashing; Hash = %s", s.Hash)
	}
	// And the corrupt file is replaced by a healthy one on Save.
	if err := cache.Save(); err != nil {
		t.Fatal(err)
	}
	rewriteKeepingStat(t, filepath.Join(home, ".codex/sessions/r.jsonl"), "world")
	if s := scanOne(t, home, LoadCache(cachePath)); s.Hash != Sha256Hex("hello") {
		t.Fatalf("Save after a corrupt load must produce a working cache; Hash = %s", s.Hash)
	}
}

func TestUnknownCacheVersionIsDiscarded(t *testing.T) {
	home := t.TempDir()
	write(t, filepath.Join(home, ".codex/sessions/r.jsonl"), "hello")
	cachePath := filepath.Join(t.TempDir(), "cache.json")
	// A well-formed file from a future format version must be ignored, not misread.
	write(t, cachePath, `{"version":999,"entries":{"codex-r":{"path":"x","mtimeNano":1,"size":5,"hash":"bogus"}}}`)

	s := scanOne(t, home, LoadCache(cachePath))
	if s.Hash != Sha256Hex("hello") {
		t.Fatalf("a version-mismatched cache must be discarded; Hash = %s", s.Hash)
	}
}

func TestUnwritableCacheNeverFailsTheScan(t *testing.T) {
	home := t.TempDir()
	write(t, filepath.Join(home, ".codex/sessions/r.jsonl"), "hello")
	// The cache path sits under a regular file, so both loading and saving fail.
	blocker := filepath.Join(t.TempDir(), "blocker")
	write(t, blocker, "i am a file, not a directory")
	cache := LoadCache(filepath.Join(blocker, "cache.json"))

	s := scanOne(t, home, cache)
	if s.Hash != Sha256Hex("hello") {
		t.Fatalf("an unusable cache must degrade to full hashing; Hash = %s", s.Hash)
	}
	if err := cache.Save(); err == nil {
		t.Fatal("Save under a non-directory should report an error (for the caller's warning)")
	}
}

func TestNilCacheMeansFullHashing(t *testing.T) {
	home := t.TempDir()
	path := filepath.Join(home, ".codex/sessions/r.jsonl")
	write(t, path, "hello")

	// Prime nothing: with a nil cache every scan reads the file, so a stat-preserving
	// rewrite is always observed. This is the --no-cache behavior.
	s := scanOne(t, home, nil)
	if s.Hash != Sha256Hex("hello") || s.Raw != "hello" {
		t.Fatalf("nil-cache scan should read fully, got hash %s raw %q", s.Hash, s.Raw)
	}
	rewriteKeepingStat(t, path, "world")
	s = scanOne(t, home, nil)
	if s.Hash != Sha256Hex("world") {
		t.Fatalf("a nil cache must re-hash every run; Hash = %s", s.Hash)
	}
}
