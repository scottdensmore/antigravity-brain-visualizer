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

// Package client talks to the visualizer's /api/ingest endpoints: it reads a
// source's manifest and pushes batches of trajectories.
package client

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Client is a thin HTTP client for the ingest API. An empty token leaves requests
// unauthenticated, which the server accepts when INGEST_TOKEN is unset.
type Client struct {
	baseURL string
	token   string
	http    *http.Client
}

// New returns a Client for baseURL, sending a bearer token when one is given.
func New(baseURL, token string) *Client {
	return &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		token:   token,
		// A finite timeout so a wedged server fails the sync instead of hanging it.
		http: &http.Client{Timeout: 60 * time.Second},
	}
}

// PushSession mirrors the server's IngestSession record; the JSON field names
// must match it exactly or the server deserializes nulls and rejects the batch.
type PushSession struct {
	Source      string `json:"source"`
	ID          string `json:"id"`
	Title       string `json:"title,omitempty"`
	SourceMtime int64  `json:"sourceMtime"`
	Raw         string `json:"raw"`
	Summary     string `json:"summary,omitempty"`
}

// PushSummary mirrors the server's IngestSummary record: a cached analysis pushed
// on its own for a session whose transcript is already stored.
type PushSummary struct {
	Source  string `json:"source"`
	ID      string `json:"id"`
	Summary string `json:"summary"`
}

// Result mirrors the server's IngestResult.
type Result struct {
	Ingested int `json:"ingested"`
	Skipped  int `json:"skipped"`
	Failed   int `json:"failed"`
}

// Manifest returns the server's id -> contentHash map for a source, so the
// caller can push only what changed.
func (c *Client) Manifest(ctx context.Context, source string) (map[string]string, error) {
	u := c.baseURL + "/api/ingest/manifest?source=" + url.QueryEscape(source)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	var manifest map[string]string
	if err := c.do(req, &manifest); err != nil {
		return nil, fmt.Errorf("fetching manifest for %s: %w", source, err)
	}
	return manifest, nil
}

// Push uploads a batch of trajectories and returns the server's tally.
func (c *Client) Push(ctx context.Context, sessions []PushSession) (Result, error) {
	payload, err := json.Marshal(sessions)
	if err != nil {
		return Result{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/api/ingest/sessions", bytes.NewReader(payload))
	if err != nil {
		return Result{}, err
	}
	req.Header.Set("Content-Type", "application/json")

	var result Result
	if err := c.do(req, &result); err != nil {
		return Result{}, fmt.Errorf("pushing %d session(s): %w", len(sessions), err)
	}
	return result, nil
}

// SummaryManifest returns the server's id -> summaryContentHash map for a source,
// so the caller can push only the summaries the store is missing or that changed.
func (c *Client) SummaryManifest(ctx context.Context, source string) (map[string]string, error) {
	u := c.baseURL + "/api/ingest/summaries/manifest?source=" + url.QueryEscape(source)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	var manifest map[string]string
	if err := c.do(req, &manifest); err != nil {
		return nil, fmt.Errorf("fetching summary manifest for %s: %w", source, err)
	}
	return manifest, nil
}

// PushSummaries uploads a batch of standalone summaries and returns the server's tally.
func (c *Client) PushSummaries(ctx context.Context, summaries []PushSummary) (Result, error) {
	payload, err := json.Marshal(summaries)
	if err != nil {
		return Result{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/api/ingest/summaries", bytes.NewReader(payload))
	if err != nil {
		return Result{}, err
	}
	req.Header.Set("Content-Type", "application/json")

	var result Result
	if err := c.do(req, &result); err != nil {
		return Result{}, fmt.Errorf("pushing %d summary(ies): %w", len(summaries), err)
	}
	return result, nil
}

// do sends the request, adds auth, treats any non-2xx as an error (never a
// silent empty result), and decodes a successful body into out.
func (c *Client) do(req *http.Request, out any) error {
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		// Name the target and hint at the cause; the bare net/http error ("dial
		// tcp …: connection refused") doesn't say what to do about it.
		return fmt.Errorf("cannot reach the visualizer at %s (is it running? check --server): %w", c.baseURL, err)
	}
	defer resp.Body.Close()

	body, readErr := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		// Report the status even if the body read was partial.
		return fmt.Errorf("%s returned %d: %s", req.URL.Path, resp.StatusCode, serverMessage(body, resp.Status))
	}
	if readErr != nil {
		return fmt.Errorf("reading %s response: %w", req.URL.Path, readErr)
	}
	if out == nil {
		return nil
	}
	if err := json.Unmarshal(body, out); err != nil {
		return fmt.Errorf("decoding %s response: %w", req.URL.Path, err)
	}
	return nil
}

// serverMessage prefers the API's {"error": "..."} text, falling back to the
// HTTP status line, so a failure reads as a cause rather than a bare code.
func serverMessage(body []byte, status string) string {
	var envelope struct {
		Error string `json:"error"`
	}
	if json.Unmarshal(body, &envelope) == nil && envelope.Error != "" {
		return envelope.Error
	}
	if len(body) > 0 {
		return strings.TrimSpace(string(body))
	}
	return status
}
