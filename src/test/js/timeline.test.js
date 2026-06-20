import { beforeEach, describe, expect, it } from "vitest";
import { renderTranscript } from "../../main/resources/public/modules/timeline.js";

let container;

beforeEach(() => {
  document.body.innerHTML = '<div id="transcript-container"></div>';
  container = document.getElementById("transcript-container");
});

describe("renderTranscript", () => {
  it("shows an empty state when there are no steps", () => {
    renderTranscript([], container);
    expect(container.querySelector(".empty-state")).not.toBeNull();
  });

  it("classifies cards by role via data attributes", () => {
    const steps = [
      { source: "USER_EXPLICIT", type: "USER_INPUT", content: "hi", created_at: "2026-06-19T10:00:00Z" },
      { source: "MODEL", type: "VIEW_FILE", content: "file body", created_at: "2026-06-19T10:00:01Z" },
      { source: "MODEL", type: "PLANNER_RESPONSE", content: "the answer", created_at: "2026-06-19T10:00:02Z" },
      { type: "ERROR_MESSAGE", status: "ERROR", content: "boom", created_at: "2026-06-19T10:00:03Z" },
    ];

    renderTranscript(steps, container);
    const cards = container.querySelectorAll(".step-card");
    expect(cards).toHaveLength(4);

    expect(cards[0].dataset.isUser).toBe("true");
    expect(cards[1].dataset.isTool).toBe("true");
    expect(cards[2].dataset.isModel).toBe("true");
    expect(cards[3].dataset.isError).toBe("true");
  });

  it("starts a new sequence wrapper at each user step", () => {
    const steps = [
      { source: "USER_EXPLICIT", type: "USER_INPUT", content: "q1", created_at: "2026-06-19T10:00:00Z" },
      { source: "MODEL", type: "VIEW_FILE", content: "x", created_at: "2026-06-19T10:00:01Z" },
      { source: "USER_EXPLICIT", type: "USER_INPUT", content: "q2", created_at: "2026-06-19T10:01:00Z" },
      { source: "MODEL", type: "PLANNER_RESPONSE", content: "done", created_at: "2026-06-19T10:01:05Z" },
    ];

    renderTranscript(steps, container);
    expect(container.querySelectorAll(".sequence-wrapper")).toHaveLength(2);
  });

  it("appends a bottom timeline marker and fires the transcriptLoaded event", () => {
    let fired = false;
    const handler = () => {
      fired = true;
    };
    window.addEventListener("transcriptLoaded", handler);

    renderTranscript(
      [{ type: "USER_INPUT", content: "hi", created_at: "2026-06-19T10:00:00Z" }],
      container
    );

    window.removeEventListener("transcriptLoaded", handler);
    expect(container.querySelector("#timeline-bottom-marker")).not.toBeNull();
    expect(fired).toBe(true);
  });
});
