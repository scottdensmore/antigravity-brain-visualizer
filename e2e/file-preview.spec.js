import { test, expect } from "./test-base.js";

test.describe("file preview modal", () => {
  test("clicking a file link opens, renders, and closes the modal", async ({ page }) => {
    await page.goto("/");
    await page.click('.conv-item:has-text("Fix the parser bug")');

    // The file link lives in a collapsed step body — expand the card that contains it.
    const linkCard = page.locator("#transcript-container .step-card", {
      has: page.locator('a[href^="file://"]'),
    });
    await linkCard.locator(".step-header").first().click();

    const link = linkCard.locator('a[href^="file://"]').first();
    await expect(link).toBeVisible();
    await link.click();

    const modal = page.locator("#file-modal");
    await expect(modal).toBeVisible();
    await expect(page.locator("#file-modal-title")).toContainText("config.txt");
    await expect(page.locator("#file-modal-content")).toContainText("parser.mode = strict");

    await page.keyboard.press("Escape");
    await expect(modal).toBeHidden();
  });
});
