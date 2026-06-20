import { test, expect } from "./test-base.js";

test.describe("Claude Code sessions", () => {
  test("switching to the Claude Code flavor lists and renders a session", async ({ page }) => {
    await page.goto("/");
    await page.selectOption("#flavor-select", "claude-code");

    const item = page.locator("#conversations-list .conv-item", {
      hasText: "Implement login flow",
    });
    await expect(item).toBeVisible();
    await item.click();

    // user prompt + assistant message + tool call + tool result = 4 steps.
    const tc = page.locator("#transcript-container");
    await expect(tc.locator(".step-card")).toHaveCount(4);

    const toolCard = tc.locator(".step-card", { has: page.locator(".tool-call") });
    await toolCard.locator(".step-header").first().click();
    await expect(toolCard.locator(".tool-name")).toContainText("Read");
  });

  test("Claude Code session statistics and cached analysis render", async ({ page }) => {
    await page.goto("/");
    await page.selectOption("#flavor-select", "claude-code");
    await page.locator('.conv-item:has-text("Implement login flow")').click();

    await expect(page.locator("#user-queries-stat-card .stat-value")).toHaveText("1");
    await expect(page.locator("#tools-stat-card .stat-value")).toHaveText("1");
    await expect(page.locator("#ai-summary-text")).toContainText(
      "implemented a login flow"
    );
  });
});
