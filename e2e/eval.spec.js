import { test, expect } from "./test-base.js";

// The saved-run tests below write real rows into the shared Postgres store, which is NOT reset
// between local runs or CI retries. Two defenses keep them honest against pre-existing rows:
//   1. saveRun() returns the new row's server-assigned savedAt, and assertions are scoped to it —
//      a leftover row from an earlier attempt can neither fake a pass nor cause a failure.
//   2. afterEach deletes every run a test saved (a no-op for rows already deleted through the UI),
//      so the history doesn't grow without bound across runs.
const savedRuns = [];

test.afterEach(async ({ request }) => {
  while (savedRuns.length > 0) {
    const savedAt = savedRuns.pop();
    await request.delete(
      `/api/eval/runs?savedAt=${encodeURIComponent(savedAt)}`
    );
  }
});

// Clicks "Save run" and returns the saved run's savedAt identifier: the one data-saved-at present
// after the history refresh that wasn't there before.
async function saveRun(page) {
  const rows = page.locator(".run-del-btn");
  const before = await rows.evaluateAll((els) =>
    els.map((el) => el.dataset.savedAt)
  );
  await page.click("#save-run-btn");
  await expect(rows).toHaveCount(before.length + 1);
  const after = await rows.evaluateAll((els) =>
    els.map((el) => el.dataset.savedAt)
  );
  const added = after.filter((s) => !before.includes(s));
  expect(added).toHaveLength(1);
  savedRuns.push(added[0]);
  return added[0];
}

// The history row (checkbox, score, delete button) for one saved run.
function runRow(page, savedAt) {
  return page.locator(
    `div:has(> button.run-del-btn[data-saved-at="${savedAt}"])`
  );
}

test.describe("Analysis Eval", () => {
  test("the Eval button scores the source's cached analyses", async ({
    page,
  }) => {
    await page.goto("/");
    await page.click("#eval-btn");

    const tc = page.locator("#transcript-container");
    await expect(tc).toContainText("Analysis Eval");
    await expect(tc).toContainText("Antigravity CLI");
    // The scoreboard cards and the active-model label render (dummy key => gemini provider).
    await expect(tc).toContainText("AVG SCORE");
    await expect(tc).toContainText("EVALUATED");
    await expect(tc).toContainText("gemini");
    // Both seeded sessions carry a cached analysis, so the pass-rate section renders.
    await expect(tc).toContainText("Check pass-rates");
    // The LLM judge is opt-in: its button is offered (the rubric itself needs a real model).
    await expect(tc.locator("#run-judge-btn")).toBeVisible();
  });

  test("eval follows the selected source", async ({ page }) => {
    await page.goto("/");
    await page.selectOption("#flavor-select", "claude-code");
    await page.click("#eval-btn");
    await expect(page.locator("#transcript-container")).toContainText("Claude Code");
  });

  test("saving a run records it in the run history", async ({ page }) => {
    await page.goto("/");
    await page.click("#eval-btn");

    const tc = page.locator("#transcript-container");
    await expect(tc).toContainText("Run history");
    const savedAt = await saveRun(page);
    // The just-saved run has its own history row (its lowercase "evaluated" is unique to history).
    await expect(runRow(page, savedAt)).toContainText("evaluated");
    await expect(tc).not.toContainText("No saved runs yet");
    // A CSV export of the history is now offered.
    await expect(tc.locator("#history-csv-btn")).toBeVisible();
  });

  test("ticking two saved runs shows an A/B comparison", async ({ page }) => {
    await page.goto("/");
    await page.click("#eval-btn");

    // Save two runs so there are two rows to compare — and tick exactly those two rows, not
    // whatever happens to sit at the top of a possibly pre-populated history.
    const first = await saveRun(page);
    const second = await saveRun(page);

    await runRow(page, second).locator(".cmp-check").check();
    await runRow(page, first).locator(".cmp-check").check();
    await expect(page.locator("#run-compare")).toContainText("Avg score");
  });

  test("deleting a saved run removes it from the history", async ({ page }) => {
    await page.goto("/");
    await page.click("#eval-btn");
    const savedAt = await saveRun(page);

    // Delete that specific run (robust to any other saved runs in the shared store).
    const del = page.locator(`.run-del-btn[data-saved-at="${savedAt}"]`);
    await expect(del).toBeVisible();
    await del.click();
    await expect(del).toHaveCount(0);
  });
});
