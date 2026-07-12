// Playwright setup project: once the server is up, push the fixture sessions into the store so the
// tests can browse them. This runs before every other project (they declare a dependency on it),
// and doubles as an end-to-end smoke test of the ingest path.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test as setup, expect } from "@playwright/test";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

setup("seed the store with fixture sessions", async ({ request }) => {
  const sessions = JSON.parse(
    fs.readFileSync(path.join(projectRoot, "build", "e2e-fixtures.json"), "utf8")
  );

  const res = await request.post("/api/ingest/sessions", { data: sessions });
  expect(res.ok(), `ingest failed: ${res.status()}`).toBeTruthy();

  // Idempotent across re-runs: a session is either ingested now or already present (skipped).
  const body = await res.json();
  expect(body.failed).toBe(0);
  expect(body.ingested + body.skipped).toBe(sessions.length);
});
