import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  filterAndSortConversations,
  loadConversations,
  selectConversation,
} from "../../main/resources/public/app.js";

// The browse→select cascade chains several un-awaited promises (renderConversationsList ->
// click -> selectConversation -> transcript fetch). Flushing the microtask queue a number of times
// lets that chain settle. The detached progress-polling loop in triggerAnalysis uses a (faked)
// setTimeout that never fires; it stays pending on purpose and is harmless to the assertions.
const flush = async () => {
  for (let i = 0; i < 15; i++) await Promise.resolve();
};

describe("filterAndSortConversations", () => {
  const convs = [
    { id: "aaa111", summary: "Fix the parser" },
    { id: "bbb222", summary: "Add a feature" },
  ];

  it("filters by summary, case-insensitively", () => {
    const out = filterAndSortConversations(convs, "PARSER", true);
    expect(out).toHaveLength(1);
    expect(out[0].id).toBe("aaa111");
  });

  it("filters by id", () => {
    const out = filterAndSortConversations(convs, "bbb", true);
    expect(out).toHaveLength(1);
    expect(out[0].summary).toBe("Add a feature");
  });

  it("returns nothing when no conversation matches", () => {
    expect(filterAndSortConversations(convs, "nothing-here", true)).toHaveLength(0);
  });

  it("reverses order when not descending and preserves it when descending", () => {
    expect(filterAndSortConversations(convs, "", true).map((c) => c.id)).toEqual([
      "aaa111",
      "bbb222",
    ]);
    expect(filterAndSortConversations(convs, "", false).map((c) => c.id)).toEqual([
      "bbb222",
      "aaa111",
    ]);
  });
});

describe("session browsing and selection (integration)", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    window.location.hash = "";
    document.body.innerHTML = `
      <select id="flavor-select"><option value="antigravity-cli" selected>cli</option></select>
      <input id="conversation-search" value="" />
      <div id="conversations-list"></div>
      <div id="conv-popover" class="hidden">
        <span id="popover-title"></span><span id="popover-id"></span><span id="popover-time"></span>
      </div>
      <div id="current-session-title"></div>
      <div id="current-session-id"></div>
      <button id="summarize-btn" disabled></button>
      <div id="transcript-container"></div>
      <div id="session-stats-container" class="hidden"></div>
      <div id="ai-summary-container" class="hidden"></div>
      <div id="ai-summary-content" class="collapsed"></div>
      <div id="ai-summary-header"><span class="chevron"></span></div>
      <div id="ai-summary-text"></div>
    `;
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function mockBackend() {
    global.fetch = vi.fn((url) => {
      if (url.includes("/transcript")) {
        return Promise.resolve({
          ok: true,
          json: () =>
            Promise.resolve([
              { type: "USER_INPUT", content: "hi", created_at: "2026-06-19T10:00:00Z" },
            ]),
        });
      }
      if (url.includes("/progress")) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ progress: 100, phase: "Done" }),
        });
      }
      if (url.includes("/summarize")) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve({ summary: "ok" }),
        });
      }
      // conversation list
      return Promise.resolve({
        ok: true,
        json: () =>
          Promise.resolve([
            { id: "aaa111", summary: "First <b>session", updatedAt: "2" },
            { id: "bbb222", summary: "Second session", updatedAt: "1" },
          ]),
      });
    });
  }

  it("loads the list, escapes summaries, and auto-selects the first session", async () => {
    mockBackend();

    await loadConversations();
    await flush();

    const items = document.querySelectorAll("#conversations-list .conv-item");
    expect(items).toHaveLength(2);
    // Summary is HTML-escaped.
    expect(items[0].querySelector(".conv-id").innerHTML).toContain("First &lt;b&gt;session");

    // First session auto-selected and its transcript rendered.
    expect(items[0].classList.contains("active")).toBe(true);
    expect(document.getElementById("current-session-id").dataset.id).toBe("aaa111");
    expect(document.getElementById("summarize-btn").disabled).toBe(false);
    expect(document.querySelector("#transcript-container .step-card")).not.toBeNull();
  });

  it("shows a failure message when the transcript request errors", async () => {
    global.fetch = vi.fn((url) => {
      if (url.includes("/transcript")) {
        return Promise.reject(new Error("boom"));
      }
      return Promise.resolve({ ok: true, json: () => Promise.resolve({ summary: "ok" }) });
    });

    const element = document.createElement("div");
    element.className = "conv-item";
    element.innerHTML = '<div class="conv-id">Some session</div>';
    document.getElementById("conversations-list").appendChild(element);

    await selectConversation("zzz999", element);
    await flush();

    expect(document.getElementById("transcript-container").innerHTML).toContain(
      "Failed to load transcript"
    );
  });
});
