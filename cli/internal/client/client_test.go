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

package client

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestManifestParsesTheIdToHashMap(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/api/ingest/manifest" {
			t.Errorf("unexpected request %s %s", r.Method, r.URL.Path)
		}
		if got := r.URL.Query().Get("source"); got != "codex" {
			t.Errorf("source = %q, want codex", got)
		}
		_, _ = io.WriteString(w, `{"a":"h1","b":"h2"}`)
	}))
	defer srv.Close()

	got, err := New(srv.URL, "").Manifest(context.Background(), "codex")
	if err != nil {
		t.Fatal(err)
	}
	if got["a"] != "h1" || got["b"] != "h2" || len(got) != 2 {
		t.Fatalf("manifest = %v", got)
	}
}

func TestPushSendsTheBatchAndReturnsCounts(t *testing.T) {
	var body []PushSession
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/ingest/sessions" {
			t.Errorf("unexpected request %s %s", r.Method, r.URL.Path)
		}
		if ct := r.Header.Get("Content-Type"); !strings.HasPrefix(ct, "application/json") {
			t.Errorf("Content-Type = %q", ct)
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		_, _ = io.WriteString(w, `{"ingested":1,"skipped":0,"failed":0}`)
	}))
	defer srv.Close()

	in := []PushSession{{Source: "codex", ID: "a", SourceMtime: 42, Raw: "x"}}
	res, err := New(srv.URL, "").Push(context.Background(), in)
	if err != nil {
		t.Fatal(err)
	}
	if res.Ingested != 1 || res.Skipped != 0 || res.Failed != 0 {
		t.Fatalf("result = %+v", res)
	}
	if len(body) != 1 || body[0].ID != "a" || body[0].SourceMtime != 42 || body[0].Raw != "x" {
		t.Fatalf("server received %+v", body)
	}
}

func TestPushSerializesTheServersFieldNames(t *testing.T) {
	var raw map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var arr []map[string]any
		_ = json.NewDecoder(r.Body).Decode(&arr)
		if len(arr) > 0 {
			raw = arr[0]
		}
		_, _ = io.WriteString(w, `{"ingested":0,"skipped":0,"failed":0}`)
	}))
	defer srv.Close()

	_, err := New(srv.URL, "").Push(context.Background(),
		[]PushSession{{Source: "codex", ID: "a", Title: "t", SourceMtime: 42, Raw: "x"}})
	if err != nil {
		t.Fatal(err)
	}
	// The field names must match the server's IngestSession record exactly, or it
	// deserializes to nulls and every push fails.
	for _, k := range []string{"source", "id", "title", "sourceMtime", "raw"} {
		if _, ok := raw[k]; !ok {
			t.Errorf("pushed JSON is missing field %q; got keys %v", k, keysOf(raw))
		}
	}
}

func TestAuthorizationHeaderSentOnlyWhenTokenSet(t *testing.T) {
	var auth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth = r.Header.Get("Authorization")
		_, _ = io.WriteString(w, `{}`)
	}))
	defer srv.Close()

	_, _ = New(srv.URL, "").Manifest(context.Background(), "codex")
	if auth != "" {
		t.Errorf("no token configured, but sent Authorization %q", auth)
	}
	_, _ = New(srv.URL, "s3cret").Manifest(context.Background(), "codex")
	if auth != "Bearer s3cret" {
		t.Errorf("Authorization = %q, want Bearer s3cret", auth)
	}
}

func TestNon2xxIsAnErrorNotASilentEmptyResult(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = io.WriteString(w, `{"error":"database unreachable"}`)
	}))
	defer srv.Close()

	if _, err := New(srv.URL, "").Manifest(context.Background(), "codex"); err == nil {
		t.Fatal("expected a 503 to surface as an error")
	}
	if _, err := New(srv.URL, "").Push(context.Background(), []PushSession{{Source: "codex", ID: "a"}}); err == nil {
		t.Fatal("expected a 503 to surface as an error")
	}
}

func TestUnauthorizedIsReportedClearly(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = io.WriteString(w, `{"error":"Ingest requires a valid Authorization: Bearer <INGEST_TOKEN>."}`)
	}))
	defer srv.Close()

	_, err := New(srv.URL, "").Push(context.Background(), []PushSession{{Source: "codex", ID: "a"}})
	if err == nil || !strings.Contains(err.Error(), "401") {
		t.Fatalf("expected a 401 error mentioning the status, got %v", err)
	}
}

func keysOf(m map[string]any) []string {
	ks := make([]string, 0, len(m))
	for k := range m {
		ks = append(ks, k)
	}
	return ks
}
