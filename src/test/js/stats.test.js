import { beforeEach, describe, expect, it } from "vitest";
import { renderStats } from "../../main/resources/public/modules/stats.js";

beforeEach(() => {
  document.body.innerHTML = '<div id="session-stats-container" class="hidden"></div>';
});

function statValue(id) {
  return document
    .querySelector(`#${id} .stat-value`)
    .textContent.trim();
}

describe("renderStats", () => {
  it("hides the container when there are no steps", () => {
    renderStats([]);
    const container = document.getElementById("session-stats-container");
    expect(container.classList.contains("hidden")).toBe(true);
  });

  it("counts user queries, tool calls, model responses and errors", () => {
    const steps = [
      { type: "USER_INPUT", content: "hi", created_at: "2026-06-19T10:00:00Z" },
      {
        source: "MODEL",
        type: "PLANNER_RESPONSE",
        content: "here is the answer",
        created_at: "2026-06-19T10:00:05Z",
      },
      {
        source: "MODEL",
        type: "PLANNER_RESPONSE",
        tool_calls: [{ name: "edit" }],
        created_at: "2026-06-19T10:00:10Z",
      },
      {
        type: "ERROR_MESSAGE",
        status: "ERROR",
        content: "boom",
        created_at: "2026-06-19T10:00:20Z",
      },
    ];

    renderStats(steps);

    expect(statValue("user-queries-stat-card")).toBe("1");
    expect(statValue("tools-stat-card")).toBe("1");
    expect(statValue("model-responses-stat-card")).toBe("1");
    expect(document.getElementById("session-stats-container").classList.contains("hidden")).toBe(
      false
    );
    // OUTCOME card reflects the error.
    expect(statValue("errors-stat-card")).toBe("Issues Detected");
  });

  it("reports success when there are no errors", () => {
    const steps = [
      { type: "USER_INPUT", content: "hi", created_at: "2026-06-19T10:00:00Z" },
    ];
    renderStats(steps);
    expect(statValue("errors-stat-card")).toBe("Succeeded");
  });

  it("counts tool steps that use a tool-like type without a tool_calls array", () => {
    const steps = [
      { type: "USER_INPUT", content: "hi", created_at: "2026-06-19T10:00:00Z" },
      { type: "VIEW_FILE", content: "...", created_at: "2026-06-19T10:00:01Z" },
      { type: "RUN_COMMAND", content: "...", created_at: "2026-06-19T10:00:02Z" },
    ];
    renderStats(steps);
    expect(statValue("tools-stat-card")).toBe("2");
  });

  it("treats a failed RUN_COMMAND as an error", () => {
    const steps = [
      { type: "USER_INPUT", content: "hi", created_at: "2026-06-19T10:00:00Z" },
      {
        type: "RUN_COMMAND",
        content: "The command failed with exit code: 1",
        created_at: "2026-06-19T10:00:01Z",
      },
    ];
    renderStats(steps);
    expect(statValue("errors-stat-card")).toBe("Issues Detected");
  });

  it("groups steps into sequences, starting a new one at each user query", () => {
    const steps = [
      { type: "USER_INPUT", content: "q1", created_at: "2026-06-19T10:00:00Z" },
      { source: "MODEL", type: "PLANNER_RESPONSE", content: "a1", created_at: "2026-06-19T10:00:05Z" },
      { type: "USER_INPUT", content: "q2", created_at: "2026-06-19T10:01:00Z" },
    ];
    renderStats(steps);
    // The DURATION card sub-label reports the number of active segments.
    const sub = document.querySelector("#duration-stat-card .stat-sub").textContent;
    expect(sub).toContain("2 active segments");
  });
});
