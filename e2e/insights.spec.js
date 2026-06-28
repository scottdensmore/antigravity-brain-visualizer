import { test, expect } from "./test-base.js";

test.describe("Fleet Insights", () => {
  test("the Insights button renders cross-session analytics for the source", async ({ page }) => {
    await page.goto("/");
    await page.click("#insights-btn");

    const tc = page.locator("#transcript-container");
    await expect(tc).toContainText("Fleet Insights");
    await expect(tc).toContainText("Antigravity CLI");
    // Overview cards: sessions / outcomes / tool calls / avg duration.
    await expect(tc.locator(".stat-card")).toHaveCount(4);
    // Tool usage and the recommendation backlog (from the seeded cached analysis).
    await expect(tc).toContainText("edit_file");
    await expect(tc).toContainText("Add a lint rule");
  });

  test("insights follow the selected source", async ({ page }) => {
    await page.goto("/");
    await page.selectOption("#flavor-select", "claude-code");
    await page.click("#insights-btn");
    await expect(page.locator("#transcript-container")).toContainText("Claude Code");
  });
});
