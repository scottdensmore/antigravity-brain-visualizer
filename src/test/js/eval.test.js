import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderEval, showEval } from "../../main/resources/public/modules/eval.js";

const REPORT = {
  flavor: "antigravity-cli",
  sessionCount: 20,
  sampledSessions: 20,
  evaluatedSessions: 8,
  avgScore: 72.5,
  modelLabel: "gemini · gemini-3.5-flash",
  checkPassRates: [
    { name: "schema-complete", count: 8 },
    { name: "has-recommendations", count: 6 },
    { name: "not-degenerate", count: 4 },
  ],
  worstCases: [
    { sessionId: "s-bad", title: "Broken run", score: 33, passed: [], failed: ["schema-complete", "not-degenerate"] },
  ],
  judge: {
    ran: false,
    note: "Run the LLM judge to add faithfulness / actionability / clarity scores.",
    judgedSessions: 0,
    cases: [],
  },
};

const JUDGED = {
  ...REPORT,
  judge: {
    ran: true,
    note: "",
    judgedSessions: 2,
    avgFaithfulness: 4.5,
    avgActionability: 3,
    avgClarity: 4,
    cases: [
      {
        sessionId: "s1",
        title: "Parser fix",
        faithfulness: 4.7,
        actionability: 3,
        clarity: 4,
        samples: 3,
        comment: "Accurate and specific.",
      },
    ],
  },
};

beforeEach(() => {
  document.body.innerHTML = '<div id="transcript-container"></div>';
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("renderEval", () => {
  it("renders the scoreboard, pass-rates, and worst cases", () => {
    const c = document.getElementById("transcript-container");
    renderEval(REPORT, c);
    const html = c.innerHTML;
    expect(html).toContain("Analysis Eval");
    expect(html).toContain("Antigravity CLI");
    expect(html).toContain("gemini · gemini-3.5-flash");
    expect(html).toContain("72.5"); // avg score
    expect(html).toContain("Has title, summary &amp; flow"); // check label
    expect(html).toContain("6/8"); // pass-rate fraction
    expect(html).toContain("Broken run"); // worst case
    expect(html).toContain("33"); // its score
  });

  it("shows a prompt to analyze when nothing has been evaluated", () => {
    const c = document.getElementById("transcript-container");
    renderEval({ ...REPORT, evaluatedSessions: 0, checkPassRates: [], worstCases: [] }, c);
    const html = c.innerHTML;
    expect(html).toContain("No analyzed sessions yet");
    // Overview cards still render.
    expect(html).toContain("AVG SCORE");
  });

  it("shows a Run-LLM-judge button and note when the judge has not run", () => {
    const c = document.getElementById("transcript-container");
    renderEval(REPORT, c);
    const html = c.innerHTML;
    expect(c.querySelector("#run-judge-btn")).not.toBeNull();
    expect(html).toContain("Run the LLM judge");
    // No rubric section yet.
    expect(html).not.toContain("FAITHFULNESS");
  });

  it("renders the rubric averages and per-case scores when the judge ran", () => {
    const c = document.getElementById("transcript-container");
    renderEval(JUDGED, c);
    const html = c.innerHTML;
    expect(html).toContain("FAITHFULNESS");
    expect(html).toContain("4.5"); // avg faithfulness
    expect(html).toContain("2 judged");
    expect(html).toContain("Parser fix");
    expect(html).toContain("Accurate and specific.");
    // Per-case shows the panel size and the ensembled (fractional) faithfulness.
    expect(html).toContain("3-judge panel");
    expect(html).toContain("F 4.7");
    // The button is gone once the judge has run.
    expect(c.querySelector("#run-judge-btn")).toBeNull();
  });

  it("clicking Run-LLM-judge re-fetches with judge=true", async () => {
    const c = document.getElementById("transcript-container");
    renderEval(REPORT, c);
    global.fetch = vi.fn(() =>
      Promise.resolve({ ok: true, json: () => Promise.resolve(JUDGED) })
    );
    c.querySelector("#run-judge-btn").click();
    await Promise.resolve();
    await Promise.resolve();
    expect(global.fetch).toHaveBeenCalledWith(expect.stringContaining("judge=true"));
  });

  it("renders run history with a delta vs the previous run", () => {
    const c = document.getElementById("transcript-container");
    const history = [
      { savedAt: "2026-07-02T10:00:00Z", flavor: "antigravity-cli", modelLabel: "gemini · v2", evaluatedSessions: 8, avgScore: 75, judged: false },
      { savedAt: "2026-07-01T10:00:00Z", flavor: "antigravity-cli", modelLabel: "gemini · v1", evaluatedSessions: 8, avgScore: 70, judged: false },
    ];
    renderEval(REPORT, c, history);
    const html = c.innerHTML;
    expect(html).toContain("Run history");
    expect(html).toContain("gemini · v2");
    expect(html).toContain("+5"); // 75 - 70
  });

  it("shows an empty-history note and a Save button when nothing is saved", () => {
    const c = document.getElementById("transcript-container");
    renderEval(REPORT, c, []);
    expect(c.innerHTML).toContain("No saved runs yet");
    expect(c.querySelector("#save-run-btn")).not.toBeNull();
  });

  it("clicking Save run POSTs the report then refreshes history", async () => {
    const c = document.getElementById("transcript-container");
    renderEval(REPORT, c, []);
    const calls = [];
    global.fetch = vi.fn((url, opts) => {
      calls.push({ url, opts });
      const isPost = opts && opts.method === "POST";
      return Promise.resolve({
        ok: true,
        json: () => Promise.resolve(isPost ? { savedAt: "x" } : []),
      });
    });
    c.querySelector("#save-run-btn").click();
    await new Promise((r) => setTimeout(r, 0));
    const post = calls.find((c) => c.opts && c.opts.method === "POST");
    expect(post).toBeTruthy();
    expect(post.url).toBe("/api/eval/runs");
    expect(JSON.parse(post.opts.body).flavor).toBe("antigravity-cli");
  });

  it("escapes dynamic values so markup can't be injected", () => {
    const c = document.getElementById("transcript-container");
    renderEval(
      { ...REPORT, worstCases: [{ sessionId: "x", title: "<script>x</script>", score: 0, failed: [] }] },
      c
    );
    expect(c.querySelector("script")).toBeNull();
    expect(c.innerHTML).toContain("&lt;script&gt;");
  });
});

describe("showEval", () => {
  it("fetches and renders the report for a flavor", async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({ ok: true, json: () => Promise.resolve(REPORT) })
    );
    await showEval("antigravity-cli");
    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining("/api/eval?flavor=antigravity-cli")
    );
    expect(document.getElementById("transcript-container").innerHTML).toContain("Analysis Eval");
  });

  it("also fetches the run history for the flavor", async () => {
    global.fetch = vi.fn((url) =>
      Promise.resolve({
        ok: true,
        json: () => Promise.resolve(url.includes("/runs") ? [] : REPORT),
      })
    );
    await showEval("antigravity-cli");
    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining("/api/eval/runs?flavor=antigravity-cli")
    );
  });

  it("shows an error message on a non-ok response", async () => {
    global.fetch = vi.fn(() => Promise.resolve({ ok: false, status: 500 }));
    await showEval("codex");
    expect(document.getElementById("transcript-container").innerHTML).toContain(
      "Failed to load the eval"
    );
  });

  it("shows an error message when the request rejects", async () => {
    global.fetch = vi.fn(() => Promise.reject(new Error("boom")));
    await showEval("codex");
    expect(document.getElementById("transcript-container").innerHTML).toContain(
      "Failed to load the eval"
    );
  });
});
