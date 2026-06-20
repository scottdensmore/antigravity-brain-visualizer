import { test, expect } from "./test-base.js";

async function openParserSession(page) {
  await page.goto("/");
  await page.click('.conv-item:has-text("Fix the parser bug")');
}

test.describe("transcript & stats", () => {
  test("selecting a session renders its transcript", async ({ page }) => {
    await openParserSession(page);
    const tc = page.locator("#transcript-container");
    await expect(tc.locator(".sequence-wrapper").first()).toBeVisible();
    await expect(tc.locator(".step-card")).toHaveCount(4);
    await expect(page.locator("#current-session-title")).toContainText("Fix the parser bug");
  });

  test("renders accurate session statistics", async ({ page }) => {
    await openParserSession(page);
    await expect(page.locator("#session-stats-container")).toBeVisible();
    await expect(page.locator("#user-queries-stat-card .stat-value")).toHaveText("1");
    await expect(page.locator("#tools-stat-card .stat-value")).toHaveText("2");
    await expect(page.locator("#model-responses-stat-card .stat-value")).toHaveText("1");
    await expect(page.locator("#errors-stat-card .stat-value")).toContainText("Issues Detected");
  });

  test("the tools stat card reveals the tool distribution chart", async ({ page }) => {
    await openParserSession(page);
    await page.click("#tools-stat-card");
    const chart = page.locator("#tools-chart");
    await expect(chart).toBeVisible();
    await expect(chart).toContainText("edit_file");
    await expect(chart).toContainText("run_command");
  });

  test("user-query filter hides non-user cards", async ({ page }) => {
    await openParserSession(page);
    await page.click("#user-queries-stat-card");
    // Only the user card stays displayed.
    const visibleCards = page.locator("#transcript-container .step-card:visible");
    await expect(visibleCards).toHaveCount(1);
  });
});
