import { test, expect } from "./test-base.js";

test.describe("Prompt Lab", () => {
  test("the Prompt Lab button renders the two prompt editors seeded with the baseline", async ({
    page,
  }) => {
    await page.goto("/");
    await page.click("#optimize-btn");

    const tc = page.locator("#transcript-container");
    await expect(tc).toContainText("Prompt Lab");
    // Both editors are present and seeded with the default instruction (served without an LLM call).
    await expect(page.locator("#opt-a")).toBeVisible();
    await expect(page.locator("#opt-b")).toBeVisible();
    await expect(page.locator("#opt-a")).not.toHaveValue("");
    await expect(page.locator("#opt-run")).toBeVisible();
  });
});
