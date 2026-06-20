// Playwright global setup: seed the fixture brain directory that the server reads via -Duser.home,
// and fail early with a helpful message if the runnable jar hasn't been built yet.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { seedFixtures } from "./fixtures.mjs";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

export default function globalSetup() {
  const home = path.join(projectRoot, "build", "e2e-home");
  seedFixtures(home);

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
