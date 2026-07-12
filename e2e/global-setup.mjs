// Playwright global setup: write the fixture config file and stage the session payloads that
// seed.setup.mjs will push to the store once the server is up. Fails early with a helpful message if
// the runnable jar hasn't been built yet.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { seedFixtures } from "./fixtures.mjs";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

export default function globalSetup() {
  const home = path.join(projectRoot, "build", "e2e-home");
  const { sessions } = seedFixtures(home);
  // The seed project (which runs once the server is up) reads these and pushes them to /api/ingest.
  fs.writeFileSync(path.join(projectRoot, "build", "e2e-fixtures.json"), JSON.stringify(sessions));

  const libs = path.join(projectRoot, "build", "libs");
  const hasJar =
    fs.existsSync(libs) &&
    fs.readdirSync(libs, { recursive: true }).some((f) => String(f).endsWith("-all.jar"));
  if (!hasJar) {
    throw new Error(
      "No runnable jar found in build/libs (expected *-all.jar).\n" +
        "Build it first with:  ./gradlew shadowJar -x test\n" +
        "(locally, run gradle under the project's JDK 25, e.g. via mise)"
    );
  }
}
