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

  test("drilling into a tally row reveals the sessions behind it", async ({ page }) => {
    await page.goto("/");
    await page.click("#insights-btn");

    const firstToolRow = page
      .locator('.drill-row[data-drill-category="tool"]')
      .first();
    await firstToolRow.locator(".drill-bar").click();
    // The expanded list shows at least one contributing session.
    await expect(firstToolRow.locator(".drill-session").first()).toBeVisible();
  });
});
