import { test, expect } from "./test-base.js";

test.describe("OpenAI Codex sessions", () => {
  test("switching to the Codex flavor lists and renders a Codex session", async ({ page }) => {
    await page.goto("/");

    await page.selectOption("#flavor-select", "codex");

    // The Codex session is listed with a title derived from the user message.
    const item = page.locator("#conversations-list .conv-item", {
      hasText: "Investigate the flaky test",
    });
    await expect(item).toBeVisible();
    await item.click();

    // The rollout is adapted into the timeline: user input, assistant message, and a tool call.
    const tc = page.locator("#transcript-container");
    await expect(tc.locator(".step-card")).toHaveCount(4);
    await expect(tc.locator(".sequence-wrapper").first()).toBeVisible();

    // Expand the function-call card and confirm the tool name renders.
    const toolCard = tc.locator(".step-card", { has: page.locator(".tool-call") });
    await toolCard.locator(".step-header").first().click();
    await expect(toolCard.locator(".tool-name")).toContainText("exec_command");
  });

  test("Codex session statistics count the tool call and user query", async ({ page }) => {
    await page.goto("/");
    await page.selectOption("#flavor-select", "codex");
    await page.locator('.conv-item:has-text("Investigate the flaky test")').click();

    await expect(page.locator("#session-stats-container")).toBeVisible();
    await expect(page.locator("#user-queries-stat-card .stat-value")).toHaveText("1");
    await expect(page.locator("#tools-stat-card .stat-value")).toHaveText("1");
  });

  test("shows the AI analysis for a Codex session", async ({ page }) => {
    await page.goto("/");
    await page.selectOption("#flavor-select", "codex");
    await page.locator('.conv-item:has-text("Investigate the flaky test")').click();

    await expect(page.locator("#ai-summary-text")).toContainText(
      "reproduced and analyzed a flaky test"
    );
  });
});
