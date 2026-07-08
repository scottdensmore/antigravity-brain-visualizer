import { test, expect } from "./test-base.js";

test.describe("Skill / AGENTS.md miner", () => {
  test("the Mine button renders mined evidence for the source", async ({ page }) => {
    await page.goto("/");
    await page.click("#mine-btn");

    const tc = page.locator("#transcript-container");
    await expect(tc).toContainText("Mined Improvements");
    await expect(tc).toContainText("Antigravity CLI");
    // The structural evidence always renders, whether or not the AI phrasing pass ran. The
    // recommendation comes from the seeded cached analysis.
    await expect(tc).toContainText("Evidence · Recommendation Backlog");
    await expect(tc).toContainText("Add a lint rule");
  });

  test("mining follows the selected source", async ({ page }) => {
    await page.goto("/");
    await page.selectOption("#flavor-select", "claude-code");
    await page.click("#mine-btn");
    await expect(page.locator("#transcript-container")).toContainText("Claude Code");
  });

  test("drilling a recommendation reveals the sessions behind it", async ({ page }) => {
    await page.goto("/");
    await page.click("#mine-btn");

    const recRow = page
      .locator('.drill-row[data-drill-category="recommendation"]')
      .first();
    await recRow.locator(".drill-bar").click();
    await expect(recRow.locator(".drill-session").first()).toBeVisible();
  });
});
