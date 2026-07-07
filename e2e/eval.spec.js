import { test, expect } from "./test-base.js";

test.describe("Analysis Eval", () => {
  test("the Eval button scores the source's cached analyses", async ({ page }) => {
    await page.goto("/");
    await page.click("#eval-btn");

    const tc = page.locator("#transcript-container");
    await expect(tc).toContainText("Analysis Eval");
    await expect(tc).toContainText("Antigravity CLI");
    // The scoreboard cards and the active-model label render (dummy key => gemini provider).
    await expect(tc).toContainText("AVG SCORE");
    await expect(tc).toContainText("EVALUATED");
    await expect(tc).toContainText("gemini");
    // Both seeded sessions carry a cached analysis, so the pass-rate section renders.
    await expect(tc).toContainText("Check pass-rates");
    // The LLM judge is opt-in: its button is offered (the rubric itself needs a real model).
    await expect(tc.locator("#run-judge-btn")).toBeVisible();
  });

  test("eval follows the selected source", async ({ page }) => {
    await page.goto("/");
    await page.selectOption("#flavor-select", "claude-code");
    await page.click("#eval-btn");
    await expect(page.locator("#transcript-container")).toContainText("Claude Code");
  });

  test("saving a run records it in the run history", async ({ page }) => {
    await page.goto("/");
    await page.click("#eval-btn");

    const tc = page.locator("#transcript-container");
    await expect(tc).toContainText("Run history");
    await page.click("#save-run-btn");
    // After saving, the history shows a run row (its lowercase "evaluated" is unique to history).
    await expect(tc).toContainText("evaluated");
    await expect(tc).not.toContainText("No saved runs yet");
  });
});
