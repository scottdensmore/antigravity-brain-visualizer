import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { triggerAnalysis } from "../../main/resources/public/modules/analysis.js";
import { state } from "../../main/resources/public/modules/utils.js";

const SUMMARY_DATA = {
  shortTitle: "Fixed The Bug",
  summary: "All good in the end",
  flow: ["did a thing"],
  agentActions: [{ action: "edit", description: "edited a file" }],
  issues: [{ error: "it broke", circumvention: "restarted" }],
  recommendations: ["add a CLI tool"],
};

beforeEach(() => {
  // Fake timers so the detached progress-polling loop's setTimeout never lingers past the test.
  vi.useFakeTimers();
  document.body.innerHTML = `
    <select id="flavor-select"><option value="antigravity-cli" selected>cli</option></select>
    <button id="summarize-btn"></button>
    <div id="ai-summary-container" class="hidden"></div>
    <div id="ai-summary-content" class="collapsed"></div>
    <div id="ai-summary-header"><span class="chevron"></span></div>
    <div id="ai-summary-text"></div>
    <div id="current-session-title"></div>
  `;
  state.summaryCache = {};
  state.currentPollSessionId = null;
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("triggerAnalysis", () => {
  it("fetches and renders all analysis sections, then caches the result", async () => {
    global.fetch = vi.fn((url) => {
      if (url.includes("/progress")) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ progress: 50, phase: "Working" }),
        });
      }
      return Promise.resolve({ ok: true, json: () => Promise.resolve(SUMMARY_DATA) });
    });

    await triggerAnalysis("session-123", true);

    const text = document.getElementById("ai-summary-text").innerHTML;
    expect(text).toContain("All good in the end");
    expect(text).toContain("Conversation Flow");
    expect(text).toContain("Agent Actions Breakdown");
    expect(text).toContain("Issues &amp; Circumventions");
    expect(text).toContain("Future Recommendations");

    // The summarize endpoint was requested.
    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining("/api/analysis/conversations/session-123/summarize")
    );
    // Result cached for next time.
    expect(state.summaryCache["session-123"]).toContain("All good in the end");
    // Session title updated from the short title.
    expect(document.getElementById("current-session-title").innerText).toBe("Fixed The Bug");
  });

  it("renders from cache without fetching when not forced", async () => {
    global.fetch = vi.fn();
    state.summaryCache["cached-session"] = "<p>cached content</p>";

    await triggerAnalysis("cached-session", false);

    expect(global.fetch).not.toHaveBeenCalled();
    expect(document.getElementById("ai-summary-text").innerHTML).toBe("<p>cached content</p>");
  });

  it("shows an error message when the fetch rejects", async () => {
    global.fetch = vi.fn(() => Promise.reject(new Error("network down")));

    await triggerAnalysis("err-session", true);

    expect(document.getElementById("ai-summary-text").innerHTML).toContain(
      "Error analyzing transcript"
    );
  });
});
