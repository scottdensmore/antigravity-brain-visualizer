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
	"encoding/json"
	"io/fs"
	"os"
	"path/filepath"
)

// cacheVersion guards the on-disk cache format; a file carrying any other
// version is discarded and rebuilt rather than misread.
const cacheVersion = 1

// DefaultCachePath returns the per-user location of the scan cache
// (<user cache dir>/agent-ingest/scan-cache.json), or "" when no user cache
// directory is available — the cache then simply never persists.
func DefaultCachePath() string {
	dir, err := os.UserCacheDir()
	if err != nil {
		return ""
	}
	return filepath.Join(dir, "agent-ingest", "scan-cache.json")
}

// cacheEntry records what was known about one session file the last time it
// was hashed. Hash is the SHA-256 of the UTF-8-sanitized content — the exact
// bytes that would be uploaded — never of the raw file bytes.
type cacheEntry struct {
	Path      string `json:"path"`
	MtimeNano int64  `json:"mtimeNano"`
	Size      int64  `json:"size"`
	Hash      string `json:"hash"`
}

// cacheFile is the on-disk shape of the cache.
type cacheFile struct {
	Version int                   `json:"version"`
	Entries map[string]cacheEntry `json:"entries"`
}

// Cache remembers each session file's content hash keyed by its path, size,
// and mtime, so a corpus that has not changed since the last run is not
// re-read and re-hashed on every invocation. It is strictly an optimization:
// a missing, corrupt, or unwritable cache degrades to full hashing and must
// never fail a run. A nil *Cache is valid and disables caching entirely.
type Cache struct {
	path    string // where Save persists; "" keeps the cache in memory only
	entries map[string]cacheEntry
}

// LoadCache reads the cache at path, returning an empty cache (never an
// error) when the file is missing, unreadable, corrupt, or from a different
// format version. An empty path yields a cache that never persists.
func LoadCache(path string) *Cache {
	c := &Cache{path: path, entries: map[string]cacheEntry{}}
	if path == "" {
		return c
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return c // missing or unreadable: start over with full hashing
	}
	var f cacheFile
	if json.Unmarshal(raw, &f) != nil || f.Version != cacheVersion || f.Entries == nil {
		return c // corrupt or from another format version: start over
	}
	c.entries = f.Entries
	return c
}

// cacheKey is the session identity the server also keys by.
func cacheKey(source, id string) string { return source + "\x00" + id }

// lookup returns the cached hash for a session file that is byte-identical to
// when it was last hashed — same path, size, and mtime — and reports a miss
// otherwise. Safe on a nil receiver.
func (c *Cache) lookup(source, id, path string, info fs.FileInfo) (string, bool) {
	if c == nil {
		return "", false
	}
	e, ok := c.entries[cacheKey(source, id)]
	if !ok || e.Hash == "" || e.Path != path || e.Size != info.Size() || e.MtimeNano != info.ModTime().UnixNano() {
		return "", false
	}
	return e.Hash, true
}

// store records the hash just computed for a session file, keyed by the stat
// taken before the read. Safe on a nil receiver.
func (c *Cache) store(source, id, path string, info fs.FileInfo, hash string) {
	if c == nil {
		return
	}
	c.entries[cacheKey(source, id)] = cacheEntry{
		Path:      path,
		MtimeNano: info.ModTime().UnixNano(),
		Size:      info.Size(),
		Hash:      hash,
	}
}

// Save persists the cache atomically (write-then-rename, so a crash never
// leaves a truncated file). The returned error is for an optional warning
// only — persisting the cache is best-effort and must never fail the run.
func (c *Cache) Save() error {
	if c == nil || c.path == "" {
		return nil
	}
	data, err := json.Marshal(cacheFile{Version: cacheVersion, Entries: c.entries})
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(c.path), 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(c.path), ".scan-cache-*")
	if err != nil {
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		os.Remove(tmp.Name())
		return err
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tmp.Name())
		return err
	}
	if err := os.Rename(tmp.Name(), c.path); err != nil {
		os.Remove(tmp.Name())
		return err
	}
	return nil
}
