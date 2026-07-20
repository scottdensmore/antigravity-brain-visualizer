import { test as base, expect } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";

const dir = path.dirname(fileURLToPath(import.meta.url));

// index.html loads marked.js from a CDN. To keep E2E runs deterministic and offline (a CDN
// hiccup would otherwise make renderTranscript throw and render zero cards), serve a vendored
// copy of the real library for any marked*.js request. IMPORTANT: index.html pins marked with a
// subresource-integrity hash, so this vendored copy must be byte-identical to the pinned version —
// a mismatched file is silently blocked by the browser and `marked` ends up undefined.
// highlight.js is intentionally left to the CDN — it is used behind a `window.hljs` guard, so its
// absence degrades gracefully.
export const test = base.extend({
  page: async ({ page }, use) => {
    await page.route(/marked.*\.js(\?.*)?$/, (route) =>
      route.fulfill({
        path: path.join(dir, "vendor", "marked.min.js"),
        contentType: "application/javascript",
      })
    );
    await use(page);
  },
});

export { expect };
