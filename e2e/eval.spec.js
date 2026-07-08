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
    // A CSV export of the history is now offered.
    await expect(tc.locator("#history-csv-btn")).toBeVisible();
  });

  test("ticking two saved runs shows an A/B comparison", async ({ page }) => {
    await page.goto("/");
    await page.click("#eval-btn");

    // Save two runs so there are two rows to compare.
    await page.click("#save-run-btn");
    await expect(page.locator("#history-csv-btn")).toBeVisible();
    await page.click("#save-run-btn");

    const boxes = page.locator(".cmp-check");
    await boxes.nth(1).check();
    await boxes.nth(0).check();
    await expect(page.locator("#run-compare")).toContainText("Avg score");
  });

  test("deleting a saved run removes it from the history", async ({ page }) => {
    await page.goto("/");
    await page.click("#eval-btn");
    await page.click("#save-run-btn");
    // Re-open the eval view so the row read below happens on a settled render (not mid-save-refresh,
    // which would let us read a stale savedAt off a row that's about to be replaced).
    await expect(page.locator("#history-csv-btn")).toBeVisible();
    await page.click("#eval-btn");

    // Target the newest run specifically (robust to any other saved runs in the shared home).
    const first = page.locator(".run-del-btn").first();
    await expect(first).toBeVisible();
    const savedAt = await first.getAttribute("data-saved-at");
    await first.click();
    await expect(
      page.locator(`.run-del-btn[data-saved-at="${savedAt}"]`)
    ).toHaveCount(0);
  });
});
