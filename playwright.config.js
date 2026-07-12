import { defineConfig, devices } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = path.dirname(fileURLToPath(import.meta.url));
const PORT = 8099;
const E2E_HOME = path.join(projectRoot, "build", "e2e-home");

// Launch the prebuilt fat jar with user.home pointed at the seeded fixtures. A dummy GEMINI_API_KEY
// lets the cached-analysis path run without contacting the real LLM (the cache lookup is gated
// behind the key check). `find` (not a top-level glob) locates the jar even when the project
// version embeds a slash — e.g. a PR's GITHUB_REF_NAME of "1/merge" nests the jar in a subdir.
// `-Ddotenv.enabled=false` keeps the run hermetic: the jar boots in the project root, so a
// developer's local .env would otherwise be picked up and change the app's AI configuration.
const serverCommand =
  `sh -c 'java -Duser.home="${E2E_HOME}" -Dmicronaut.server.port=${PORT} ` +
  `-Ddotenv.enabled=false ` +
  `-jar "$(find build/libs -name "*-all.jar" | head -1)"'`;

export default defineConfig({
  testDir: "./e2e",
  globalSetup: "./e2e/global-setup.mjs",
  timeout: 30_000,
  expect: { timeout: 7_000 },
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI
    ? [["github"], ["html", { open: "never" }]]
    : "list",
  use: {
    baseURL: `http://localhost:${PORT}`,
    trace: "on-first-retry",
  },
  projects: [
    // Pushes the fixture sessions to the store once the server is up; every test depends on it.
    { name: "setup", testMatch: /.*\.setup\.mjs/ },
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
      dependencies: ["setup"],
    },
  ],
  webServer: {
    command: serverCommand,
    url: `http://localhost:${PORT}/api/brain/conversations`,
    // Locally reuse a running server for speed. Note: globalSetup re-seeds the fixture files (the
    // controllers read them per-request) but does NOT restart the server, so if you change the jar
    // or the -Duser.home target, kill the server on :8099 first. CI always starts fresh.
    reuseExistingServer: !process.env.CI,
    timeout: 90_000,
    env: { GEMINI_API_KEY: "dummy-e2e-key", TZ: "UTC" },
  },
});
