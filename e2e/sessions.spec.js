import { test, expect } from "./test-base.js";

test.describe("session browsing", () => {
  test("lists the seeded sessions", async ({ page }) => {
    await page.goto("/");
    const items = page.locator("#conversations-list .conv-item");
    await expect(items).toHaveCount(2);
    await expect(page.locator(".conv-item", { hasText: "Fix the parser bug" })).toBeVisible();
    await expect(page.locator(".conv-item", { hasText: "Add dark mode" })).toBeVisible();
  });

  test("search filters the list", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("#conversations-list .conv-item")).toHaveCount(2);
    await page.fill("#conversation-search", "parser");
    await expect(page.locator("#conversations-list .conv-item")).toHaveCount(1);
    await expect(page.locator("#conversations-list .conv-item")).toContainText(
      "Fix the parser bug"
    );
  });

  test("sort toggle reverses the order (newest-first by default)", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("#conversations-list .conv-item").first()).toContainText(
      "Add dark mode"
    );
    await page.click("#sort-conversations-btn");
    await expect(page.locator("#conversations-list .conv-item").first()).toContainText(
      "Fix the parser bug"
    );
  });

  test("switching flavor loads that flavor's sessions", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("#conversations-list .conv-item")).toHaveCount(2);
    await page.selectOption("#flavor-select", "antigravity-ide");
    await expect(page.locator("#conversations-list .conv-item")).toHaveCount(1);
    await expect(page.locator("#conversations-list .conv-item")).toContainText(
      "IDE refactor session"
    );
  });
});
