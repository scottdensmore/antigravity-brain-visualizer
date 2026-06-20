import { test, expect } from "./test-base.js";

test.describe("AI analysis", () => {
  test("auto-loads the cached analysis for a session", async ({ page }) => {
    await page.goto("/");
    await page.click('.conv-item:has-text("Fix the parser bug")');

    const ai = page.locator("#ai-summary-text");
    await expect(ai).toContainText("null-pointer bug in the parser");
    await expect(ai).toContainText("Conversation Flow");
    await expect(ai).toContainText("Issues & Circumventions");
    await expect(ai).toContainText("Added a null check");
    await expect(ai).toContainText("Future Recommendations");
  });

  test("recompute fetches and renders a fresh analysis", async ({ page }) => {
    // Stub the LLM-backed endpoints so recompute never hits the real model. The body differs by
    // whether force=true is present, so we can prove the button triggered a *recompute* rather than
    // just re-showing what auto-load already rendered.
    await page.route("**/api/analysis/conversations/*/summarize**", (route) => {
      const isForce = route.request().url().includes("force=true");
      route.fulfill({
        json: {
          shortTitle: isForce ? "Recomputed" : "Cached",
          summary: isForce ? "Freshly recomputed summary text" : "Cached summary text",
          flow: [],
          agentActions: [],
          issues: [],
          recommendations: [],
        },
      });
    });
    await page.route("**/api/analysis/conversations/*/progress**", (route) =>
      route.fulfill({ json: { phase: "Done", progress: 100 } })
    );

    await page.goto("/");
    await page.click('.conv-item:has-text("Fix the parser bug")');
    // Auto-load (force=false) shows the cached body first.
    await expect(page.locator("#ai-summary-text")).toContainText("Cached summary text");

    await page.click("#summarize-btn");
    await expect(page.locator("#ai-summary-text")).toContainText(
      "Freshly recomputed summary text"
    );
    await expect(page.locator("#summarize-btn")).toBeEnabled();
  });
});
