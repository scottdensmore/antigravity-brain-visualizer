import { test, expect } from "./test-base.js";

test.describe("layout & navigation chrome", () => {
  test("toggling the sidebar collapses and expands it", async ({ page }) => {
    await page.goto("/");
    const sidebar = page.locator(".sidebar");
    await expect(sidebar).not.toHaveClass(/collapsed/);
    await page.click("#sidebar-toggle-btn");
    await expect(sidebar).toHaveClass(/collapsed/);
    await page.click("#sidebar-toggle-btn");
    await expect(sidebar).not.toHaveClass(/collapsed/);
  });

  test("dragging the resizer changes the sidebar width", async ({ page }) => {
    await page.goto("/");
    const resizer = page.locator("#sidebar-resizer");
    const box = await resizer.boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(360, box.y + box.height / 2, { steps: 8 });
    await page.mouse.up();

    const width = await page.evaluate(() =>
      getComputedStyle(document.documentElement).getPropertyValue("--sidebar-width").trim()
    );
    const px = parseInt(width, 10);
    expect(px).toBeGreaterThanOrEqual(200);
    expect(px).toBeLessThanOrEqual(800);
  });

  test("the timeline scrubber thumb tracks transcript scrolling", async ({ page }) => {
    await page.setViewportSize({ width: 1200, height: 320 });
    await page.goto("/");
    await page.click('.conv-item:has-text("Fix the parser bug")');

    // Expand every card so the transcript overflows its container and becomes scrollable.
    for (const header of await page.locator("#transcript-container .step-header").all()) {
      await header.click();
    }

    const tc = page.locator("#transcript-container");
    // Fail loudly if the transcript didn't render at all (e.g. marked failed to load), rather than
    // letting the scrollability skip below mask it.
    await expect(tc.locator(".step-card").first()).toBeVisible();

    const scrollable = await tc.evaluate((el) => el.scrollHeight > el.clientHeight + 5);
    test.skip(!scrollable, "transcript does not overflow in this viewport");

    const thumb = page.locator("#timeline-thumb");
    const before = await thumb.evaluate((el) => el.style.top || "0px");
    await tc.evaluate((el) => {
      el.scrollTop = el.scrollHeight;
      el.dispatchEvent(new Event("scroll"));
    });
    await expect
      .poll(async () => thumb.evaluate((el) => el.style.top || "0px"))
      .not.toBe(before);
  });
});
