import { describe, expect, it } from "vitest";
import { historyCsv } from "../../main/resources/public/modules/eval-export.js";

const RUNS = [
  {
    savedAt: "2026-07-02T10:00:00Z",
    flavor: "antigravity-cli",
    modelLabel: "gemini · v2",
    sessionCount: 20,
    evaluatedSessions: 8,
    avgScore: 75,
    judged: true,
    judgedSessions: 3,
    avgFaithfulness: 4.5,
    avgActionability: 3,
    avgClarity: 4,
  },
  {
    savedAt: "2026-07-01T10:00:00Z",
    flavor: "antigravity-cli",
    modelLabel: "gemini · v1",
    sessionCount: 20,
    evaluatedSessions: 8,
    avgScore: 70,
    judged: false,
    judgedSessions: 0,
    avgFaithfulness: 0,
    avgActionability: 0,
    avgClarity: 0,
  },
];

describe("historyCsv", () => {
  it("emits a header row and one row per run", () => {
    const csv = historyCsv(RUNS);
    const lines = csv.trimEnd().split("\n");
    expect(lines).toHaveLength(3); // header + 2 runs
    expect(lines[0]).toBe(
      "savedAt,flavor,modelLabel,sessionCount,evaluatedSessions,avgScore,judged,judgedSessions,avgFaithfulness,avgActionability,avgClarity"
    );
    expect(lines[1]).toContain("2026-07-02T10:00:00Z,antigravity-cli,gemini · v2,20,8,75,true,3,4.5,3,4");
    expect(lines[2]).toContain(",70,false,0,0,0,0");
  });

  it("quotes and escapes fields containing commas or quotes", () => {
    const csv = historyCsv([
      { savedAt: "t", flavor: "codex", modelLabel: 'weird, "quoted" model', avgScore: 1 },
    ]);
    expect(csv).toContain('"weird, ""quoted"" model"');
  });

  it("handles an empty history (header only)", () => {
    const csv = historyCsv([]);
    expect(csv.trimEnd().split("\n")).toHaveLength(1);
    expect(csv).toContain("savedAt,flavor,modelLabel");
  });
});
